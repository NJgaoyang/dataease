package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * An allowlist on field IDs, not a request from the frontend to hide columns.
 * The final projection is enforced by the core Dataset PermissionManage service.
 */
@Service
@ConditionalOnProperty(prefix="dataease.enhance",name="enabled",havingValue="true")
public class EnhanceDatasetColumnPolicy {
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceIds ids;

    public EnhanceDatasetColumnPolicy(JdbcTemplate jdbc, EnhanceAcl acl, EnhanceIds ids) {
        this.jdbc=jdbc;
        this.acl=acl;
        this.ids=ids;
    }

    /**
     * Deny by default: missing dataset read permission or no allowed fields means no columns.
     * Super administrators are handled by the core adapter and are not subject to this list.
     */
    public Set<Long> allowedColumns(long datasetId) {
        EnhanceSession.Identity current=EnhanceSession.current();
        if (current.superAdmin()) {
            throw new IllegalStateException("System administrators bypass the column allowlist");
        }
        if (datasetId<=0 ||
                !acl.permitted("dataset",String.valueOf(datasetId),"read",current.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dataset access denied");
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT c.field_id FROM de_ext_dataset_column_allow c " +
                "JOIN de_ext_user_role ur ON ur.role_id=c.role_id " +
                "JOIN de_ext_role r ON r.id=c.role_id " +
                "JOIN de_ext_resource_registry res ON res.resource_type='dataset' " +
                "AND res.resource_id=c.dataset_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND res.org_id=? " +
                "AND c.dataset_id=?",
                Long.class,current.userId(),current.orgId(),current.orgId(),String.valueOf(datasetId)));
    }

    @Transactional
    public void replaceColumns(long roleId,long datasetId,List<Long> columns) {
        if (datasetId<=0 || columns==null || columns.size()>5000 ||
                columns.stream().anyMatch(id -> id==null || id<=0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid dataset fields");
        }
        List<Map<String,Object>> roles=jdbc.queryForList(
                "SELECT org_id,code FROM de_ext_role WHERE id=?",roleId);
        if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long oid=((Number)roles.get(0).get("org_id")).longValue();
        acl.requireOrgAdmin(oid);
        if("SYS_ADMIN".equals(String.valueOf(roles.get(0).get("code"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Integer owner=jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_resource_registry " +
                "WHERE resource_type='dataset' AND resource_id=? AND org_id=?",
                Integer.class,String.valueOf(datasetId),oid);
        if(owner==null || owner!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Dataset not registered to the role organization");
        jdbc.update("DELETE FROM de_ext_dataset_column_allow WHERE role_id=? AND dataset_id=?",
                roleId,String.valueOf(datasetId));
        for(Long field:new LinkedHashSet<>(columns)) {
            jdbc.update("INSERT INTO de_ext_dataset_column_allow " +
                    "(role_id,dataset_id,field_id) VALUES (?,?,?)",
                    roleId,String.valueOf(datasetId),field);
        }
        jdbc.update("INSERT INTO de_ext_audit(id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(),EnhanceSession.current().userId(),"DATASET_COLUMN_ALLOW",
                roleId+":"+datasetId,System.currentTimeMillis());
    }
}