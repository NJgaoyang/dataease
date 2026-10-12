package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
                "JOIN de_ext_role r ON ur.role_id=r.id WHERE ur.user_id=? AND r.org_id=? " +
                "AND a.resource_type=? AND a.resource_id=? AND a.action=?",
                Integer.class, p.userId(), oid, type, resourceId, action);
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
}