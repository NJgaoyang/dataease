package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceAcl {
    private final JdbcTemplate jdbc;
    public EnhanceAcl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireSystemAdmin() {
        if (!EnhanceSession.current().superAdmin())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System administrator required");
    }

    public boolean member(long uid, long oid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?", Integer.class, uid, oid);
        return count != null && count > 0;
    }

    public boolean organizationAdmin(long uid, long oid) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                "JOIN de_ext_membership m ON m.user_id=ur.user_id AND m.org_id=r.org_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND r.code='ORG_ADMIN'",
                Integer.class, uid, oid);
        return count != null && count > 0;
    }

    public void requireOrgAdmin(long oid) {
        var principal = EnhanceSession.current();
        if (!principal.superAdmin() && !organizationAdmin(principal.userId(), oid))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization administrator required");
    }

    public boolean permitted(String type, String resourceId, String action, long oid) {
        var p = EnhanceSession.current();
        if (p.superAdmin()) return true;
        if (!member(p.userId(), oid)) return false;
        Integer allowed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_resource_acl a JOIN de_ext_user_role ur ON a.role_id=ur.role_id " +
                "JOIN de_ext_role r ON ur.role_id=r.id " +
                "JOIN de_ext_resource_registry own ON own.resource_type=a.resource_type " +
                "AND own.resource_id=a.resource_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND own.org_id=? " +
                "AND a.resource_type=? AND a.resource_id=? AND a.action=?",
                Integer.class, p.userId(), oid, oid, type, resourceId, action);
        return allowed != null && allowed > 0;
    }

    public boolean menuAllowed(String menuCode, long oid) {
        var p = EnhanceSession.current();
        if (p.superAdmin()) return true;
        if (!member(p.userId(), oid)) return false;
        Integer allowed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_menu_acl a JOIN de_ext_user_role ur ON a.role_id=ur.role_id " +
                "JOIN de_ext_role r ON r.id=ur.role_id WHERE ur.user_id=? AND r.org_id=? AND a.menu_code=?",
                Integer.class, p.userId(), oid, menuCode);
        return allowed != null && allowed > 0;
    }

    public void requireResource(String type, String id, String action, long oid) {
        if (!permitted(type,id,action,oid))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Resource access denied");
    }
    /** Shared, validated grant shape used by both platform and organization administrators. */
    public record Grant(String resourceType, String resourceId, String action) {}

    private static String required(String input,int max) {
        if (input == null || input.isBlank() || input.length() > max)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid resource ACL");
        return input.trim();
    }

    /**
     * Validate every proposed grant BEFORE modifying the existing ACL.
     * A role may receive only resources explicitly registered to its organization.
     */
    @Transactional
    public void replaceRoleResources(long roleId,List<Grant> grants) {
        if (grants == null || grants.size() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        List<Map<String,Object>> roles = jdbc.queryForList(
                "SELECT id,org_id,code FROM de_ext_role WHERE id=?",roleId);
        if (roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String,Object> role = roles.get(0);
        long oid=((Number)role.get("org_id")).longValue();
        requireOrgAdmin(oid);
        if ("SYS_ADMIN".equals(String.valueOf(role.get("code"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var validated = new java.util.LinkedHashMap<String,Grant>();
        for (Grant grant:grants) {
            if (grant == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            String type = required(grant.resourceType(),80);
            String resourceId = required(grant.resourceId(),128);
            String action = required(grant.action(),32);
            if (!Set.of("read","manage","export").contains(action))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid action");
            Integer owned = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_resource_registry " +
                    "WHERE resource_type=? AND resource_id=? AND org_id=?",
                    Integer.class,type,resourceId,oid);
            if (owned == null || owned != 1)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Resource not registered to this organization");
            validated.put(type + "|" + resourceId + "|" + action,new Grant(type,resourceId,action));
        }
        jdbc.update("DELETE FROM de_ext_resource_acl WHERE role_id=?",roleId);
        for (Grant grant:validated.values()) {
            jdbc.update("INSERT INTO de_ext_resource_acl " +
                    "(role_id,resource_type,resource_id,action) VALUES (?,?,?,?)",
                    roleId,grant.resourceType(),grant.resourceId(),grant.action());
        }
    }

}