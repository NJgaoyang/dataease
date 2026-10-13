package io.dataease.enhance;

import io.dataease.api.permissions.org.dto.*;
import io.dataease.api.permissions.org.vo.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Legacy DataEase OrgApi URL adapter. Uses only self-owned de_ext_* metadata.
 * No changes are made to DataEase upstream resource tables.
 */
@RestController
@RequestMapping("/org")
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceOrgCompatServer {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceAdminController admin;

    public EnhanceOrgCompatServer(JdbcTemplate jdbc, EnhanceAcl acl, EnhanceAdminController admin) {
        this.jdbc = jdbc;
        this.acl = acl;
        this.admin = admin;
    }

    @Transactional
    @PostMapping("/page/create")
    public Long create(@RequestBody OrgCreator creator) {
        acl.requireSystemAdmin();
        if (creator == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        long oid = Long.parseLong(admin.addOrg(new EnhanceAdminController.NewOrg(
                creator.getName(), creator.getPid())).get("id"));
        List<Long> admins = creator.getAdminIds() == null ? List.of() : creator.getAdminIds();
        if (admins.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Long role = jdbc.queryForObject(
                "SELECT id FROM de_ext_role WHERE org_id=? AND code='ORG_ADMIN'", Long.class, oid);
        if (role == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        for (Long uid : new HashSet<>(admins)) {
            if (uid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            admin.addMembership(new EnhanceAdminController.Membership(uid, oid));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(uid, role));
        }
        return oid;
    }

    @Transactional
    @PostMapping("/page/edit")
    public void edit(@RequestBody OrgEditor editor) {
        acl.requireSystemAdmin();
        if (editor == null || editor.getId() == null ||
                editor.getName() == null || editor.getName().isBlank() ||
                editor.getName().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        int rows = jdbc.update("UPDATE de_ext_org SET name=? WHERE id=?",
                editor.getName().trim(), editor.getId());
        if (rows != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @Transactional
    @PostMapping("/page/delete")
    public void delete(@RequestBody OrgDelRequest request) {
        acl.requireSystemAdmin();
        if (request == null || request.getId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (request.isDelResource())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Resource cascading deletion is not supported by the enhancement");
        admin.deleteOrg(request.getId());
    }

    @GetMapping("/detail/{oid}")
    public OrgDetailVO detail(@PathVariable long oid) {
        requireAccess(oid);
        var row = organization(oid);
        OrgDetailVO result = new OrgDetailVO();
        result.setId(oid);
        result.setName(String.valueOf(row.get("name")));
        Number parent = (Number) row.get("parent_id");
        result.setPid(parent == null ? null : parent.longValue());
        result.setRootPath("0");
        return result;
    }

    @PostMapping("/page/lazyTree")
    public LazyTreeVO lazyTree(@RequestBody(required = false) OrgLazyRequest request) {
        var principal = EnhanceSession.current();
        Long pid = request == null ? null : request.getPid();
        String keyword = request == null ? null : request.getKeyword();
        List<Map<String,Object>> rows = principal.superAdmin()
                ? jdbc.queryForList("SELECT id,parent_id,name FROM de_ext_org WHERE enabled=1 ORDER BY id")
                : jdbc.queryForList(
                    "SELECT o.id,o.parent_id,o.name FROM de_ext_org o " +
                    "JOIN de_ext_membership m ON m.org_id=o.id " +
                    "WHERE m.user_id=? AND o.enabled=1 ORDER BY o.id", principal.userId());
        Set<Long> visible = new HashSet<>();
        for(var row:rows) visible.add(((Number)row.get("id")).longValue());
        List<LazyOrgTreeNode> nodes = new ArrayList<>();
        for(var row:rows) {
            long oid = ((Number)row.get("id")).longValue();
            Number parent = (Number)row.get("parent_id");
            boolean isRoot = parent == null || !visible.contains(parent.longValue());
            boolean matchingParent = pid == null ? isRoot : parent != null && pid.equals(parent.longValue());
            String name = String.valueOf(row.get("name"));
            if (!matchingParent || keyword != null && !name.contains(keyword)) continue;
            LazyOrgTreeNode node = new LazyOrgTreeNode();
            node.setId(oid);
            node.setPid(parent == null ? null : parent.longValue());
            node.setName(name);
            node.setReadOnly(!principal.superAdmin());
            node.setHasChildren(rows.stream().anyMatch(item -> {
                Number parentId=(Number)item.get("parent_id");
                return parentId != null && parentId.longValue() == oid;
            }));
            nodes.add(node);
        }
        LazyTreeVO result = new LazyTreeVO();
        result.setNodes(nodes);
        result.setExpandKeyList(List.of());
        return result;
    }

    @GetMapping("/resourceExist/{oid}")
    public boolean resourceExist(@PathVariable long oid) {
        acl.requireSystemAdmin();
        organization(oid);
        // Deliberately inspect only enhancement-owned relationships.
        // Legacy DataEase resources cannot be cascade-deleted through this API.
        for (String sql : List.of(
                "SELECT COUNT(*) FROM de_ext_org WHERE parent_id=?",
                "SELECT COUNT(*) FROM de_ext_membership WHERE org_id=?",
                "SELECT COUNT(*) FROM de_ext_role WHERE org_id=? AND system_role=0",
                "SELECT COUNT(*) FROM de_ext_user_role ur JOIN de_ext_role r ON r.id=ur.role_id WHERE r.org_id=?",
                "SELECT COUNT(*) FROM de_ext_menu_acl a JOIN de_ext_role r ON r.id=a.role_id WHERE r.org_id=?",
                "SELECT COUNT(*) FROM de_ext_resource_acl a JOIN de_ext_role r ON r.id=a.role_id WHERE r.org_id=?",
                "SELECT COUNT(*) FROM de_ext_resource_registry WHERE org_id=?",
                "SELECT COUNT(*) FROM de_ext_dataset_column_allow c JOIN de_ext_role r ON r.id=c.role_id WHERE r.org_id=?")) {
            Integer count = jdbc.queryForObject(sql, Integer.class, oid);
            if (count != null && count > 0) return true;
        }
        return false;
    }

    private void requireAccess(long oid) {
        var current=EnhanceSession.current();
        if(!current.superAdmin() && !acl.member(current.userId(),oid))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private Map<String,Object> organization(long id) {
        var rows=jdbc.queryForList("SELECT id,parent_id,name FROM de_ext_org WHERE id=? AND enabled=1",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
}