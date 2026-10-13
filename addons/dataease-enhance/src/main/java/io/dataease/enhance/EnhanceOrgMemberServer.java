package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Compatibility endpoints for V3 organization membership screens.
 * Identifiers returned to JavaScript are strings to preserve 64-bit Snowflake precision.
 */
@RestController
@RequestMapping("/sys/org/member")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceOrgMemberServer {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceOrgGovernanceController governance;
    private final EnhanceIds ids;

    public EnhanceOrgMemberServer(JdbcTemplate jdbc, EnhanceAcl acl,
                                  EnhanceOrgGovernanceController governance, EnhanceIds ids) {
        this.jdbc=jdbc;
        this.acl=acl;
        this.governance=governance;
        this.ids=ids;
    }

    public record MembersInput(Long orgId, Integer current, Integer page, Integer pageSize, Integer size, String keyword) {}
    public record RoleOptions(Long orgId) {}
    public record MembersAdd(Long orgId, List<Long> userIds, List<Long> roleIds) {}
    public record RoleSwitch(Long orgId, Long userId, List<Long> roleIds) {}

    private static long org(Long oid) {
        if (oid==null || oid<=0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization is required");
        return oid;
    }

    @PostMapping("/page")
    public Map<String,Object> page(@RequestBody MembersInput input) {
        if(input==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long oid=org(input.orgId());
        acl.requireOrgAdmin(oid);
        int page=input.current()!=null?input.current():input.page()==null?1:input.page();
        int size=input.pageSize()!=null?input.pageSize():input.size()==null?10:input.size();
        if(page<1 || page>100000 || size<1 || size>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String keyword=input.keyword()==null?"":input.keyword().trim();
        if(keyword.length()>128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String filter="WHERE m.org_id=? AND u.deleted=0";
        List<Object> args=new ArrayList<>(List.of(oid));
        if(!keyword.isEmpty()){
            filter+=" AND (u.account LIKE ? OR u.display_name LIKE ?)";
            args.add("%"+keyword+"%");
            args.add("%"+keyword+"%");
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_membership m " +
                "JOIN de_ext_user u ON u.id=m.user_id "+filter,Long.class,args.toArray());
        List<Object> query=new ArrayList<>(args);
        query.add(size);
        query.add((page-1)*size);
        List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> person:jdbc.queryForList(
                "SELECT u.id,u.account,u.display_name,u.email,u.enabled,m.org_id FROM de_ext_membership m " +
                "JOIN de_ext_user u ON u.id=m.user_id "+filter+
                " ORDER BY u.id LIMIT ? OFFSET ?",query.toArray())){
            long uid=((Number)person.get("id")).longValue();
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",String.valueOf(uid));
            row.put("account",person.get("account"));
            row.put("name",person.get("display_name"));
            row.put("email",person.get("email"));
            row.put("enable",((Number)person.get("enabled")).intValue()==1);
            row.put("orgId",String.valueOf(oid));
            List<Map<String,String>> roles=new ArrayList<>();
            for(Map<String,Object> role:jdbc.queryForList(
                    "SELECT r.id,r.name FROM de_ext_role r JOIN de_ext_user_role ur ON ur.role_id=r.id " +
                    "WHERE ur.user_id=? AND r.org_id=?",uid,oid)){
                roles.add(Map.of("id",String.valueOf(role.get("id")),"name",String.valueOf(role.get("name"))));
            }
            row.put("roleItems",roles);
            items.add(row);
        }
        return Map.of("records",items,"total",total,"pages",(total+size-1)/size);
    }

    @PostMapping("/candidates")
    public List<Map<String,String>> candidates(@RequestBody(required=false) Map<String,Object> request){
        // Organization administrators may not enumerate accounts in unrelated organizations.
        acl.requireSystemAdmin();
        String keyword=request==null || request.get("keyword")==null?"":String.valueOf(request.get("keyword")).trim();
        if(keyword.length()>128) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String pattern="%"+keyword+"%";
        List<Map<String,String>> result=new ArrayList<>();
        for(Map<String,Object> person:jdbc.queryForList(
                "SELECT id,account,display_name FROM de_ext_user WHERE deleted=0 AND enabled=1 " +
                "AND (account LIKE ? OR display_name LIKE ?) ORDER BY id LIMIT 200",pattern,pattern)){
            result.add(Map.of("id",String.valueOf(person.get("id")),
                    "account",String.valueOf(person.get("account")),
                    "name",String.valueOf(person.get("display_name"))));
        }
        return result;
    }

    @PostMapping("/roleOptions")
    public List<Map<String,Object>> roleOptions(@RequestBody RoleOptions request) {
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long oid=org(request.orgId());
        acl.requireOrgAdmin(oid);
        List<Map<String,Object>> roles=new ArrayList<>();
        for(Map<String,Object> role:jdbc.queryForList(
                "SELECT id,code,name FROM de_ext_role WHERE org_id=? AND code <> 'SYS_ADMIN' ORDER BY id",oid)){
            roles.add(Map.of("id",String.valueOf(role.get("id")),
                    "name",String.valueOf(role.get("name")),
                    "root","ORG_ADMIN".equals(String.valueOf(role.get("code")))));
        }
        return roles;
    }

    @Transactional
    @PostMapping("/add")
    public void add(@RequestBody MembersAdd request) {
        if(request==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long oid=org(request.orgId());
        acl.requireSystemAdmin();
        Integer organization = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_org WHERE id=? AND enabled=1",Integer.class,oid);
        if(organization==null || organization!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization not found");
        if(request.userIds()==null || request.userIds().isEmpty() || request.userIds().size()>100 ||
                request.userIds().stream().anyMatch(Objects::isNull) || request.roleIds()==null ||
                request.roleIds().size()>100 || request.roleIds().stream().anyMatch(Objects::isNull)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        for(Long uid:new HashSet<>(request.userIds())){
            Integer exists=jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_user WHERE id=? AND deleted=0 AND enabled=1",
                    Integer.class,uid);
            if(exists==null || exists!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Integer roleCount=jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_role WHERE org_id=? AND code <> 'SYS_ADMIN'",Integer.class,oid);
        Set<Long> roleIds=new LinkedHashSet<>(request.roleIds());
        if(roleCount==null || roleIds.size()>roleCount) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        for(Long roleId:roleIds) {
            Integer match=jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_role WHERE id=? AND org_id=? AND code<>'SYS_ADMIN'",
                    Integer.class,roleId,oid);
            if(match==null || match!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        for(Long uid:new HashSet<>(request.userIds())){
            Integer exists=jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",Integer.class,uid,oid);
            if(exists==null || exists==0)
                jdbc.update("INSERT INTO de_ext_membership(user_id,org_id) VALUES (?,?)",uid,oid);
            governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(
                    oid,uid,new ArrayList<>(roleIds)));
        }
        audit("ORG_MEMBER_ADD",oid+":"+request.userIds().size());
    }

    @Transactional
    @PostMapping("/switchRole")
    public void switchRole(@RequestBody RoleSwitch request){
        if(request==null || request.userId()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(
                org(request.orgId()),request.userId(),request.roleIds()));
    }

    @Transactional
    @PostMapping("/remove/{uid}/{oid}")
    public void remove(@PathVariable long uid,@PathVariable long oid) {
        acl.requireOrgAdmin(oid);
        if(uid==1L || uid==EnhanceSession.current().userId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cannot remove self or platform administrator");
        Integer membership=jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",
                Integer.class,uid,oid);
        if(membership==null || membership!=1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // Do not remove the final organization administrator; keep the org recoverable.
        if(acl.organizationAdmin(uid,oid)){
            Integer adminCount=jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT ur.user_id) FROM de_ext_user_role ur " +
                    "JOIN de_ext_role r ON r.id=ur.role_id " +
                    "JOIN de_ext_membership m ON m.user_id=ur.user_id AND m.org_id=r.org_id " +
                    "JOIN de_ext_user u ON u.id=ur.user_id " +
                    "WHERE r.org_id=? AND r.code='ORG_ADMIN' AND u.enabled=1 AND u.deleted=0",
                    Integer.class,oid);
            if(adminCount==null || adminCount<=1)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Last organization administrator");
        }
        jdbc.update("DELETE FROM de_ext_user_role WHERE user_id=? AND role_id IN " +
                "(SELECT id FROM de_ext_role WHERE org_id=?)",uid,oid);
        jdbc.update("DELETE FROM de_ext_membership WHERE user_id=? AND org_id=?",uid,oid);
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",uid);
        audit("ORG_MEMBER_REMOVE",oid+":"+uid);
    }

    private void audit(String action,String subject) {
        jdbc.update("INSERT INTO de_ext_audit(id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(),EnhanceSession.current().userId(),action,subject,System.currentTimeMillis());
    }
}