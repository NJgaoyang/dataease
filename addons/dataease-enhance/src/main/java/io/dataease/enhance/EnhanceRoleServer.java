package io.dataease.enhance;

import io.dataease.api.permissions.role.dto.RoleCreator;
import io.dataease.api.permissions.role.dto.RoleEditor;
import io.dataease.api.permissions.role.vo.RoleVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/role")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceRoleServer {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceAdminController admin;
    private final EnhanceIds ids;

    public EnhanceRoleServer(JdbcTemplate jdbc, EnhanceAcl acl, EnhanceAdminController admin, EnhanceIds ids) {
        this.jdbc=jdbc; this.acl=acl; this.admin=admin; this.ids=ids;
    }

    @GetMapping("/query")
    public List<RoleVO> query() { return queryWithOid(EnhanceSession.current().orgId()); }

    @GetMapping("/queryWithOid/{oid}")
    public List<RoleVO> queryWithOid(@PathVariable long oid) {
        var session=EnhanceSession.current();
        if(!session.superAdmin() && !acl.member(session.userId(),oid))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<RoleVO> roles=new ArrayList<>();
        for(Map<String,Object> row:jdbc.queryForList(
                "SELECT id,name,system_role FROM de_ext_role WHERE org_id=? ORDER BY id",oid)) {
            var role=new RoleVO();
            role.setId(((Number)row.get("id")).longValue());
            role.setName(String.valueOf(row.get("name")));
            role.setReadonly(((Number)row.get("system_role")).intValue()!=0);
            role.setRoot(false);
            roles.add(role);
        }
        return roles;
    }

    @PostMapping("/create")
    public Long create(@RequestBody RoleCreator request) {
        acl.requireSystemAdmin();
        String code="CUSTOM_"+ids.next();
        return Long.valueOf(admin.addRole(new EnhanceAdminController.NewRole(
                EnhanceSession.current().orgId(),code,request.getName())).get("id"));
    }

    @Transactional
    @PostMapping("/edit")
    public void edit(@RequestBody RoleEditor request) {
        acl.requireSystemAdmin();
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_role WHERE id=? AND system_role=0",
                Integer.class,request.getId());
        if(count==null || count!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(request.getName()==null || request.getName().isBlank() || request.getName().length()>255)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("UPDATE de_ext_role SET name=? WHERE id=?",request.getName().trim(),request.getId());
    }

    @Transactional
    @PostMapping("/delete/{rid}")
    public void delete(@PathVariable long rid) {
        acl.requireSystemAdmin();
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_role WHERE id=? AND system_role=0",Integer.class,rid);
        if(count==null || count!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        jdbc.update("DELETE FROM de_ext_menu_acl WHERE role_id=?",rid);
        jdbc.update("DELETE FROM de_ext_resource_acl WHERE role_id=?",rid);
        List<Long> uids=jdbc.queryForList("SELECT user_id FROM de_ext_user_role WHERE role_id=?",Long.class,rid);
        jdbc.update("DELETE FROM de_ext_user_role WHERE role_id=?",rid);
        jdbc.update("DELETE FROM de_ext_role WHERE id=?",rid);
        for(Long id:uids) jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?",id);
    }
}