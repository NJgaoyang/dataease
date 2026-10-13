package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Scoped organization administrator operations.
 * A custom organization administrator may assign only roles belonging to that organization.
 * Neither a client supplied user ID nor a role ID can expand the acting organization.
 */
@RestController
@RequestMapping("/enhance/org")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceOrgGovernanceController {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceIds ids;

    public EnhanceOrgGovernanceController(JdbcTemplate jdbc, EnhanceAcl acl, EnhanceIds ids) {
        this.jdbc = jdbc;
        this.acl = acl;
        this.ids = ids;
    }

    public record RoleChange(long orgId, long userId, List<Long> roleIds) {}
    public record RoleCreate(long orgId, String name) {}

    @GetMapping("/members")
    public List<Map<String, Object>> members(@RequestParam long orgId) {
        acl.requireOrgAdmin(orgId);
        return jdbc.queryForList(
                "SELECT u.id,u.account,u.display_name,u.enabled,m.org_id " +
                "FROM de_ext_membership m JOIN de_ext_user u ON u.id=m.user_id " +
                "WHERE m.org_id=? ORDER BY u.account", orgId);
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles(@RequestParam long orgId) {
        acl.requireOrgAdmin(orgId);
        return jdbc.queryForList(
                "SELECT id,org_id,code,name,system_role FROM de_ext_role WHERE org_id=? ORDER BY id", orgId);
    }

    @Transactional
    @PostMapping("/roles")
    public Map<String, String> createRole(@RequestBody RoleCreate request) {
        acl.requireOrgAdmin(request.orgId());
        if (request.name() == null || request.name().isBlank() || request.name().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role name is required");
        }
        long id = ids.next();
        jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,0)",
                id, request.orgId(), "CUSTOM_" + id, request.name().trim());
        audit("ORG_ROLE_CREATE", String.valueOf(id));
        return Map.of("id", String.valueOf(id));
    }

    /**
     * Atomic full role replacement, excluding the global SYS_ADMIN privilege.
     * Last remaining organization administrator cannot demote themselves.
     */
    @Transactional
    @PutMapping("/members/roles")
    public void replaceMemberRoles(@RequestBody RoleChange request) {
        acl.requireOrgAdmin(request.orgId());
        if (request.roleIds() == null || request.roleIds().size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid roles");
        }
        Integer member = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",
                Integer.class, request.userId(), request.orgId());
        if (member == null || member == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found");
        }

        Set<Long> roles = new HashSet<>(request.roleIds());
        if (!roles.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(roles.size(), "?"));
            List<Object> bind = new ArrayList<>();
            bind.add(request.orgId());
            bind.addAll(roles);
            Long allowed = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_role WHERE org_id=? AND id IN (" + placeholders + ") " +
                    "AND code <> 'SYS_ADMIN'",
                    Long.class, bind.toArray());
            if (allowed == null || allowed != roles.size()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role belongs to another organization");
            }
        }

        List<Long> existing = jdbc.queryForList(
                "SELECT ur.role_id FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND r.code <> 'SYS_ADMIN'",
                Long.class, request.userId(), request.orgId());
        boolean removesOrgAdmin = existing.stream().anyMatch(role -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_role WHERE id=? AND code='ORG_ADMIN'", Integer.class, role) > 0)
                && roles.stream().noneMatch(role -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_role WHERE id=? AND code='ORG_ADMIN'", Integer.class, role) > 0);
        if (removesOrgAdmin && request.userId() == EnhanceSession.current().userId()) {
            Integer admins = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                    "WHERE r.org_id=? AND r.code='ORG_ADMIN'", Integer.class, request.orgId());
            if (admins != null && admins <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the final organization administrator");
            }
        }

        jdbc.update("DELETE FROM de_ext_user_role WHERE user_id=? AND role_id IN " +
                "(SELECT id FROM de_ext_role WHERE org_id=? AND code <> 'SYS_ADMIN')",
                request.userId(), request.orgId());
        for (Long id : roles) {
            jdbc.update("INSERT INTO de_ext_user_role (user_id,role_id) VALUES (?,?)", request.userId(), id);
        }
        jdbc.update("UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?", request.userId());
        audit("ORG_MEMBER_ROLES", request.orgId() + ":" + request.userId());
    }

    @Transactional
    @PutMapping("/roles/{id}/resources")
    public void replaceRoleResources(@PathVariable long id,@RequestBody List<EnhanceAcl.Grant> grants) {
        acl.replaceRoleResources(id,grants);
        audit("ORG_RESOURCE_ACL",String.valueOf(id));
    }

    @Transactional
    @PutMapping("/roles/{id}/menus")
    public void replaceRoleMenus(@PathVariable long id,@RequestBody List<String> menus) {
        List<Map<String,Object>> roles=jdbc.queryForList(
                "SELECT org_id,code FROM de_ext_role WHERE id=?",id);
        if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var role=roles.get(0);
        long oid=((Number)role.get("org_id")).longValue();
        acl.requireOrgAdmin(oid);
        if("SYS_ADMIN".equals(String.valueOf(role.get("code"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(menus==null || menus.size()>500 || menus.stream().anyMatch(
                code -> code==null || code.isBlank() || code.length()>128))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        jdbc.update("DELETE FROM de_ext_menu_acl WHERE role_id=?",id);
        for(String code:new HashSet<>(menus)) {
            jdbc.update("INSERT INTO de_ext_menu_acl(role_id,menu_code) VALUES (?,?)",id,code.trim());
        }
        audit("ORG_MENU_ACL",String.valueOf(id));
    }

    private void audit(String action, String subject) {
        jdbc.update("INSERT INTO de_ext_audit(id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(), EnhanceSession.current().userId(), action, subject, System.currentTimeMillis());
    }
}