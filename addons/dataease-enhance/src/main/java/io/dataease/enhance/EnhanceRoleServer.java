package io.dataease.enhance;

import io.dataease.api.permissions.role.dto.*;
import io.dataease.api.permissions.role.vo.RoleDetailVO;
import io.dataease.api.permissions.role.vo.RoleVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Compatible with the existing DataEase RoleApi URL / DTO contracts.
 * Every role mutation re-checks the role's organization in the database.
 */
@RestController
@RequestMapping("/role")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceRoleServer {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceOrgGovernanceController governance;
    private final EnhanceIds ids;

    public EnhanceRoleServer(JdbcTemplate jdbc, EnhanceAcl acl,
                             EnhanceOrgGovernanceController governance, EnhanceIds ids) {
        this.jdbc = jdbc;
        this.acl = acl;
        this.governance = governance;
        this.ids = ids;
    }

    @GetMapping("/query")
    public List<RoleVO> query() {
        return queryWithOid(EnhanceSession.current().orgId());
    }

    @GetMapping("/queryWithOid/{oid}")
    public List<RoleVO> queryWithOid(@PathVariable long oid) {
        var principal = EnhanceSession.current();
        if (!principal.superAdmin() && !acl.member(principal.userId(), oid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<RoleVO> result = new ArrayList<>();
        for (var row : jdbc.queryForList(
                "SELECT id,name,system_role,code FROM de_ext_role WHERE org_id=? ORDER BY id", oid)) {
            RoleVO role = new RoleVO();
            role.setId(((Number) row.get("id")).longValue());
            role.setName(String.valueOf(row.get("name")));
            role.setReadonly(((Number) row.get("system_role")).intValue() != 0);
            role.setRoot("SYS_ADMIN".equals(String.valueOf(row.get("code"))));
            result.add(role);
        }
        return result;
    }

    @GetMapping("/detail/{rid}")
    public RoleDetailVO detail(@PathVariable long rid) {
        var row = role(rid);
        var actor = EnhanceSession.current();
        long orgId = ((Number) row.get("org_id")).longValue();
        if (!actor.superAdmin() && !acl.member(actor.userId(), orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        RoleDetailVO vo = new RoleDetailVO();
        vo.setId(rid);
        vo.setName(String.valueOf(row.get("name")));
        vo.setTypeCode(((Number) row.get("system_role")).intValue());
        return vo;
    }

    @PostMapping("/create")
    public Long create(@RequestBody RoleCreator request) {
        long oid = EnhanceSession.current().orgId();
        return Long.valueOf(governance.createRole(
                new EnhanceOrgGovernanceController.RoleCreate(oid, request.getName())).get("id"));
    }

    @Transactional
    @PostMapping("/edit")
    public void edit(@RequestBody RoleEditor request) {
        if (request == null || request.getId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var row = managedCustomRole(request.getId());
        if (request.getName() == null || request.getName().isBlank() || request.getName().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        jdbc.update("UPDATE de_ext_role SET name=? WHERE id=?", request.getName().trim(), request.getId());
        audit("ROLE_EDIT", String.valueOf(request.getId()));
    }

    @Transactional
    @PostMapping("/delete/{rid}")
    public void delete(@PathVariable long rid) {
        managedCustomRole(rid);
        List<Long> userIds = jdbc.queryForList(
                "SELECT user_id FROM de_ext_user_role WHERE role_id=?", Long.class, rid);
        jdbc.update("DELETE FROM de_ext_menu_acl WHERE role_id=?", rid);
        jdbc.update("DELETE FROM de_ext_resource_acl WHERE role_id=?", rid);
        jdbc.update("DELETE FROM de_ext_dataset_column_allow WHERE role_id=?", rid);
        jdbc.update("DELETE FROM de_ext_user_role WHERE role_id=?", rid);
        jdbc.update("DELETE FROM de_ext_role WHERE id=?", rid);
        userIds.forEach(user -> jdbc.update(
                "UPDATE de_ext_user SET token_version=token_version+1 WHERE id=?", user));
        audit("ROLE_DELETE", String.valueOf(rid));
    }

    @PostMapping("/user/option")
    public List<RoleVO> optionForUser(@RequestBody RoleRequest request) {
        long oid = request.getOid() == null ? EnhanceSession.current().orgId() : request.getOid();
        acl.requireOrgAdmin(oid);
        if (request.getUid() != null && !acl.member(request.getUid(), oid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return queryWithOid(oid);
    }

    @PostMapping("/user/selected")
    public List<RoleVO> selectedForUser(@RequestBody RoleRequest request) {
        if (request.getUid() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long oid = request.getOid() == null ? EnhanceSession.current().orgId() : request.getOid();
        acl.requireOrgAdmin(oid);
        if (!acl.member(request.getUid(), oid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        List<Long> assigned = jdbc.queryForList(
                "SELECT ur.role_id FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.org_id=?", Long.class, request.getUid(), oid);
        return queryWithOid(oid).stream().filter(r -> assigned.contains(r.getId())).toList();
    }

    @Transactional
    @PostMapping("/mountUser")
    public void mountUser(@RequestBody MountUserRequest request) {
        if (request == null || request.getRid() == null || request.getUids() == null ||
                request.getUids().size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var role = role(request.getRid());
        long oid = ((Number) role.get("org_id")).longValue();
        acl.requireOrgAdmin(oid);
        if ("SYS_ADMIN".equals(String.valueOf(role.get("code")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        for (Long uid : new HashSet<>(request.getUids())) {
            if (uid == null || !acl.member(uid, oid)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        for (Long uid : new HashSet<>(request.getUids())) {
            List<Long> assigned = currentRoles(uid, oid);
            if (!assigned.contains(request.getRid())) assigned.add(request.getRid());
            governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(oid, uid, assigned));
        }
    }

    @Transactional
    @PostMapping("/unMountUser")
    public void unMountUser(@RequestBody UnmountUserRequest request) {
        if (request == null || request.getRid() == null || request.getUid() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var role = role(request.getRid());
        long oid = ((Number) role.get("org_id")).longValue();
        acl.requireOrgAdmin(oid);
        if ("SYS_ADMIN".equals(String.valueOf(role.get("code")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<Long> assigned = currentRoles(request.getUid(), oid);
        assigned.remove(request.getRid());
        governance.replaceMemberRoles(new EnhanceOrgGovernanceController.RoleChange(
                oid, request.getUid(), assigned));
    }

    private List<Long> currentRoles(long uid, long oid) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT ur.role_id FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND r.code <> 'SYS_ADMIN'",
                Long.class, uid, oid));
    }

    private Map<String, Object> role(long id) {
        List<Map<String, Object>> matches = jdbc.queryForList(
                "SELECT id,org_id,code,name,system_role FROM de_ext_role WHERE id=?", id);
        if (matches.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return matches.get(0);
    }

    private Map<String, Object> managedCustomRole(long rid) {
        var role = role(rid);
        acl.requireOrgAdmin(((Number) role.get("org_id")).longValue());
        if (((Number) role.get("system_role")).intValue() != 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System role cannot be modified");
        }
        return role;
    }

    private void audit(String action, String subject) {
        jdbc.update("INSERT INTO de_ext_audit(id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(), EnhanceSession.current().userId(), action, subject, System.currentTimeMillis());
    }
}