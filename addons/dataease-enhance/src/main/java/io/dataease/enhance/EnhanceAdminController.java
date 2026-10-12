package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.Pattern;

/** Owner-only administration. Clients must not supply an acting user or tenant identity. */
@RestController
@RequestMapping("/enhance/admin")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceAdminController {
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{2,63}");
    private final JdbcTemplate jdbc;
    private final EnhanceIds ids;
    private final EnhanceAcl acl;
    private final BCryptPasswordEncoder encoder;

    public EnhanceAdminController(JdbcTemplate jdbc, EnhanceIds ids, EnhanceAcl acl,
                                  BCryptPasswordEncoder encoder) {
        this.jdbc=jdbc;this.ids=ids;this.acl=acl;this.encoder=encoder;
    }

    public record NewUser(String account, String name, String password, Long orgId) {}
    public record NewOrg(String name, Long parentId) {}
    public record NewRole(long orgId, String code, String name) {}
    public record Grant(String resourceType, String resourceId, String action) {}
    public record Membership(long userId, long orgId) {}
    public record RoleAssignment(long userId, long roleId) {}
    public record Toggle(boolean enabled) {}
    public record ChangePassword(String password) {}

    private void audit(String action,String subject) {
        jdbc.update("INSERT INTO de_ext_audit (id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(),EnhanceSession.current().userId(),action,subject,System.currentTimeMillis());
    }
    private void countMustBeOne(String sql,Object... params) {
        Integer n=jdbc.queryForObject(sql,Integer.class,params);
        if(n==null || n!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Resource not found");
    }
    private static String nonblank(String text,int max) {
        if(text==null || text.isBlank() || text.length()>max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid name");
        return text.trim();
    }

    @GetMapping("/users")
    public List<Map<String,Object>> users() {
        acl.requireSystemAdmin();
        return jdbc.queryForList("SELECT id,account,display_name,enabled,locked_until,created_at FROM de_ext_user ORDER BY created_at DESC");
    }

    @Transactional
    @PostMapping("/users")
    public Map<String,String> addUser(@RequestBody NewUser request) {
        acl.requireSystemAdmin();
        if(request==null || request.account()==null || !ACCOUNT.matcher(request.account()).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid account");
        String name=nonblank(request.name(),255);
        String account=request.account().trim().toLowerCase(Locale.ROOT);
        if(request.password()==null || request.password().length()<12)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must have 12+ characters");
        long oid=request.orgId()==null?EnhanceSession.current().orgId():request.orgId();
        countMustBeOne("SELECT COUNT(*) FROM de_ext_org WHERE id=? AND enabled=1",oid);
        long uid=ids.next();
        Integer exists=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_user WHERE account=?",Integer.class,account);
        if(exists!=null && exists>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account exists");
        jdbc.update("INSERT INTO de_ext_user (id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                uid,account,name,encoder.encode(request.password()),1,0,0L,0,System.currentTimeMillis());
        jdbc.update("INSERT INTO de_ext_membership (user_id,org_id) VALUES (?,?)",uid,oid);
        audit("USER_CREATE",String.valueOf(uid));
        return Map.of("id",String.valueOf(uid));
    }

    @Transactional
    @PostMapping("/users/{id}/enabled")
    public void toggle(@PathVariable long id,@RequestBody Toggle request) {
        acl.requireSystemAdmin();
        if(id==1L && !request.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bootstrap admin must remain enabled");
        countMustBeOne("SELECT COUNT(*) FROM de_ext_user WHERE id=?",id);
        jdbc.update("UPDATE de_ext_user SET enabled=?,token_version=token_version+1 WHERE id=?",request.enabled()?1:0,id);
        audit("USER_STATUS",String.valueOf(id));
    }

    @Transactional
    @PostMapping("/users/{id}/reset-password")
    public void resetPassword(@PathVariable long id,@RequestBody ChangePassword request) {
        acl.requireSystemAdmin();
        if(request.password()==null || request.password().length()<12)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must have 12+ characters");
        countMustBeOne("SELECT COUNT(*) FROM de_ext_user WHERE id=?",id);
        jdbc.update("UPDATE de_ext_user SET password_hash=?,failed_attempts=0,locked_until=0,token_version=token_version+1 WHERE id=?",
                encoder.encode(request.password()),id);
        audit("USER_PASSWORD_RESET",String.valueOf(id));
    }

    @GetMapping("/orgs")
    public List<Map<String,Object>> orgs() {
        acl.requireSystemAdmin();
        return jdbc.queryForList("SELECT id,parent_id,name,enabled FROM de_ext_org ORDER BY id");
    }

    @Transactional
    @PostMapping("/orgs")
    public Map<String,String> addOrg(@RequestBody NewOrg request) {
        acl.requireSystemAdmin();
        long parent=request.parentId()==null?1L:request.parentId();
        countMustBeOne("SELECT COUNT(*) FROM de_ext_org WHERE id=? AND enabled=1",parent);
        long id=ids.next();
        jdbc.update("INSERT INTO de_ext_org (id,parent_id,name,enabled) VALUES (?,?,?,1)",id,parent,nonblank(request.name(),255));
        jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,1)",
                ids.next(),id,"ORG_ADMIN","组织管理员");
        audit("ORG_CREATE",String.valueOf(id));
        return Map.of("id",String.valueOf(id));
    }

    @Transactional
    @DeleteMapping("/orgs/{id}")
    public void deleteOrg(@PathVariable long id) {
        acl.requireSystemAdmin();
        if(id==1L) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot delete root");
        countMustBeOne("SELECT COUNT(*) FROM de_ext_org WHERE id=?",id);
        for(String sql:List.of("SELECT COUNT(*) FROM de_ext_org WHERE parent_id=?",
                "SELECT COUNT(*) FROM de_ext_membership WHERE org_id=?",
                "SELECT COUNT(*) FROM de_ext_role WHERE org_id=? AND system_role=0",
                "SELECT COUNT(*) FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id WHERE r.org_id=?",
                "SELECT COUNT(*) FROM de_ext_menu_acl a JOIN de_ext_role r ON r.id=a.role_id WHERE r.org_id=?",
                "SELECT COUNT(*) FROM de_ext_resource_acl a JOIN de_ext_role r ON r.id=a.role_id WHERE r.org_id=?")) {
            Integer count=jdbc.queryForObject(sql,Integer.class,id);
            if(count!=null && count>0) throw new ResponseStatusException(HttpStatus.CONFLICT,"Organization is not empty");
        }
        // Only the internally-created, unused ORG_ADMIN role may be removed.
        jdbc.update("DELETE FROM de_ext_role WHERE org_id=? AND code='ORG_ADMIN' AND system_role=1",id);
        jdbc.update("DELETE FROM de_ext_org WHERE id=?",id);
        audit("ORG_DELETE",String.valueOf(id));
    }

    @Transactional
    @PostMapping("/memberships")
    public void addMembership(@RequestBody Membership request) {
        acl.requireSystemAdmin();
        countMustBeOne("SELECT COUNT(*) FROM de_ext_user WHERE id=? AND enabled=1",request.userId());
        countMustBeOne("SELECT COUNT(*) FROM de_ext_org WHERE id=? AND enabled=1",request.orgId());
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",
                Integer.class,request.userId(),request.orgId());
        if(n==null || n==0) jdbc.update("INSERT INTO de_ext_membership (user_id,org_id) VALUES (?,?)",request.userId(),request.orgId());
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",request.userId());
        audit("MEMBER_ADD",request.userId()+":"+request.orgId());
    }

    @Transactional
    @DeleteMapping("/memberships/{uid}/{oid}")
    public void removeMembership(@PathVariable long uid,@PathVariable long oid) {
        acl.requireSystemAdmin();
        if(uid==1L && oid==1L) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot remove root admin");
        jdbc.update("DELETE FROM de_ext_user_role WHERE user_id=? AND role_id IN (SELECT id FROM de_ext_role WHERE org_id=?)",uid,oid);
        jdbc.update("DELETE FROM de_ext_membership WHERE user_id=? AND org_id=?",uid,oid);
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",uid);
        audit("MEMBER_REMOVE",uid+":"+oid);
    }

    @GetMapping("/roles")
    public List<Map<String,Object>> roles(@RequestParam long orgId) {
        acl.requireSystemAdmin();
        return jdbc.queryForList("SELECT id,org_id,code,name,system_role FROM de_ext_role WHERE org_id=? ORDER BY id",orgId);
    }

    @Transactional
    @PostMapping("/roles")
    public Map<String,String> addRole(@RequestBody NewRole request) {
        acl.requireSystemAdmin();
        countMustBeOne("SELECT COUNT(*) FROM de_ext_org WHERE id=? AND enabled=1",request.orgId());
        String code=nonblank(request.code(),128);
        if(!Pattern.matches("[A-Z][A-Z0-9_]{2,63}",code) || code.equals("SYS_ADMIN") || code.equals("ORG_ADMIN"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid custom role code");
        long id=ids.next();
        jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,0)",
                id,request.orgId(),code,nonblank(request.name(),255));
        audit("ROLE_CREATE",String.valueOf(id));
        return Map.of("id",String.valueOf(id));
    }

    @Transactional
    @PostMapping("/assign-role")
    public void assignRole(@RequestBody RoleAssignment request) {
        acl.requireSystemAdmin();
        List<Long> orgs=jdbc.queryForList("SELECT org_id FROM de_ext_role WHERE id=?",Long.class,request.roleId());
        if(orgs.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Role not found");
        countMustBeOne("SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",
                request.userId(),orgs.get(0));
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_user_role WHERE user_id=? AND role_id=?",
                Integer.class,request.userId(),request.roleId());
        if(n==null || n==0) jdbc.update("INSERT INTO de_ext_user_role (user_id,role_id) VALUES (?,?)",request.userId(),request.roleId());
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",request.userId());
        audit("ROLE_ASSIGN",request.userId()+":"+request.roleId());
    }

    @Transactional
    @DeleteMapping("/assign-role/{uid}/{rid}")
    public void revokeRole(@PathVariable long uid,@PathVariable long rid) {
        acl.requireSystemAdmin();
        if(uid==1L && rid==1L) throw new ResponseStatusException(HttpStatus.CONFLICT,"Cannot revoke root admin");
        jdbc.update("DELETE FROM de_ext_user_role WHERE user_id=? AND role_id=?",uid,rid);
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",uid);
        audit("ROLE_REVOKE",uid+":"+rid);
    }

    /**
     * The platform admin may configure an organization's built-in ORG_ADMIN role,
     * but the global SYS_ADMIN role cannot be modified through an ACL grant.
     */
    private void customRole(long id) {
        countMustBeOne("SELECT COUNT(*) FROM de_ext_role WHERE id=? AND code <> 'SYS_ADMIN'",id);
    }

    @Transactional
    @PutMapping("/roles/{id}/menus")
    public void replaceMenus(@PathVariable long id,@RequestBody List<String> codes) {
        acl.requireSystemAdmin();customRole(id);
        if(codes==null || codes.size()>500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("DELETE FROM de_ext_menu_acl WHERE role_id=?",id);
        for(String code:new HashSet<>(codes)) {
            jdbc.update("INSERT INTO de_ext_menu_acl (role_id,menu_code) VALUES (?,?)",id,nonblank(code,128));
        }
        audit("MENU_ACL",String.valueOf(id));
    }

    @Transactional
    @PutMapping("/roles/{id}/resources")
    public void replaceResources(@PathVariable long id,@RequestBody List<Grant> grants) {
        acl.requireSystemAdmin();customRole(id);
        if(grants==null || grants.size()>2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("DELETE FROM de_ext_resource_acl WHERE role_id=?",id);
        var unique=new HashSet<String>();
        for(Grant g:grants) {
            String type=nonblank(g.resourceType(),80);
            String rid=nonblank(g.resourceId(),128);
            String action=nonblank(g.action(),32);
            if(!Set.of("read","manage","export").contains(action))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid action");
            if(unique.add(type+"|"+rid+"|"+action)) {
                jdbc.update("INSERT INTO de_ext_resource_acl (role_id,resource_type,resource_id,action) VALUES (?,?,?,?)",id,type,rid,action);
            }
        }
        audit("RESOURCE_ACL",String.valueOf(id));
    }

    @GetMapping("/audit")
    public List<Map<String,Object>> audit(@RequestParam(defaultValue="100") int limit) {
        acl.requireSystemAdmin();
        return jdbc.queryForList("SELECT id,actor_id,action,subject,created_at FROM de_ext_audit ORDER BY created_at DESC LIMIT ?",
                Math.max(1,Math.min(500,limit)));
    }
}