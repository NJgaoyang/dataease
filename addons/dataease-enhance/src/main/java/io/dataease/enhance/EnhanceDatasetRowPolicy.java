package io.dataease.enhance;

import io.dataease.api.permissions.dataset.dto.DataSetRowPermissionsTreeDTO;
import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeItem;
import io.dataease.extensions.view.dto.DatasetRowPermissionsTreeObj;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Strict row equality policies, materialized as native DataEase SQL filter trees. */
@Service
@ConditionalOnProperty(prefix="dataease.enhance",name="enabled",havingValue="true")
public class EnhanceDatasetRowPolicy {
    public record Rule(Long fieldId,String value) {}
    private final JdbcTemplate jdbc;
    private final EnhanceAcl acl;
    private final EnhanceIds ids;
    private EnhanceDatasetFieldLookup fieldLookup;

    public EnhanceDatasetRowPolicy(JdbcTemplate jdbc,EnhanceAcl acl,EnhanceIds ids) {
        this.jdbc=jdbc;this.acl=acl;this.ids=ids;
    }

    @Autowired(required=false)
    public void setFieldLookup(EnhanceDatasetFieldLookup lookup) {
        this.fieldLookup=lookup;
    }

    private EnhanceDatasetFieldLookup lookup() {
        if(fieldLookup==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dataset field metadata adapter is unavailable");
        return fieldLookup;
    }

    /** A missing rule is never interpreted as unrestricted access. */
    public List<DataSetRowPermissionsTreeDTO> forCurrentUser(Long datasetId,Long queriedUser) {
        EnhanceSession.Identity actor=EnhanceSession.current();
        if(actor.superAdmin()) return List.of();
        if(datasetId==null || datasetId<=0 ||
                (queriedUser!=null && queriedUser!=actor.userId()) ||
                !acl.permitted("dataset",String.valueOf(datasetId),"read",actor.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dataset access denied");
        }
        List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT x.field_id,x.field_value FROM de_ext_dataset_row_eq x " +
                "JOIN de_ext_user_role ur ON ur.role_id=x.role_id " +
                "JOIN de_ext_role r ON r.id=ur.role_id " +
                "JOIN de_ext_resource_registry res ON res.resource_type='dataset' " +
                "AND res.resource_id=x.dataset_id " +
                "WHERE ur.user_id=? AND r.org_id=? AND res.org_id=? AND x.dataset_id=? " +
                "AND EXISTS(SELECT 1 FROM de_ext_resource_acl a WHERE a.role_id=x.role_id " +
                "AND a.resource_type='dataset' AND a.resource_id=x.dataset_id AND a.action='read')",
                actor.userId(),actor.orgId(),actor.orgId(),String.valueOf(datasetId));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "No dataset row scope assigned");
        if(rows.size()>1000) throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Too many row scope predicates");
        List<DataSetRowPermissionsTreeDTO> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            long fid=((Number)row.get("field_id")).longValue();
            DatasetTableFieldDTO field=lookup().requireField(datasetId,fid);
            String value=(String)row.get("field_value");
            if(value==null || value.isBlank() || value.length()>255)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid stored row scope");
            DatasetRowPermissionsTreeItem condition=new DatasetRowPermissionsTreeItem();
            condition.setType("item");
            condition.setFieldId(fid);
            condition.setField(field);
            condition.setFilterType("logic");
            condition.setTerm("eq");
            condition.setValue(value);
            DatasetRowPermissionsTreeObj tree=new DatasetRowPermissionsTreeObj();
            tree.setLogic("and");
            tree.setItems(List.of(condition));
            DataSetRowPermissionsTreeDTO dto=new DataSetRowPermissionsTreeDTO();
            dto.setEnable(true);
            dto.setDatasetId(datasetId);
            dto.setAuthTargetType("role");
            dto.setTree(tree);
            dto.setExportData(false);
            result.add(dto);
        }
        return result;
    }

    @Transactional
    public void replace(long roleId,long datasetId,List<Rule> rules) {
        if(datasetId<=0 || rules==null || rules.isEmpty() || rules.size()>100 ||
                rules.stream().anyMatch(Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Non-empty dataset row scopes are required");
        }
        List<Map<String,Object>> roles=jdbc.queryForList(
                "SELECT org_id,code FROM de_ext_role WHERE id=?",roleId);
        if(roles.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        long oid=((Number)roles.get(0).get("org_id")).longValue();
        acl.requireOrgAdmin(oid);
        if("SYS_ADMIN".equals(String.valueOf(roles.get(0).get("code"))))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Integer owned=jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_resource_registry " +
                "WHERE resource_type='dataset' AND resource_id=? AND org_id=?",
                Integer.class,String.valueOf(datasetId),oid);
        if(owned==null || owned!=1)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Dataset ownership mismatch");

        Set<Rule> unique=new LinkedHashSet<>();
        for(Rule rule:rules) {
            if(rule.fieldId()==null || rule.fieldId()<=0 || rule.value()==null ||
                    rule.value().isBlank() || rule.value().length()>255 ||
                    rule.value().chars().anyMatch(Character::isISOControl))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid row predicate");
            lookup().requireField(datasetId,rule.fieldId());
            unique.add(rule);
        }
        jdbc.update("DELETE FROM de_ext_dataset_row_eq WHERE role_id=? AND dataset_id=?",
                roleId,String.valueOf(datasetId));
        for(Rule rule:unique) {
            jdbc.update("INSERT INTO de_ext_dataset_row_eq " +
                    "(id,role_id,dataset_id,field_id,field_value) VALUES (?,?,?,?,?)",
                    ids.next(),roleId,String.valueOf(datasetId),
                    rule.fieldId(),rule.value());
        }
        jdbc.update("INSERT INTO de_ext_audit(id,actor_id,action,subject,created_at) VALUES (?,?,?,?,?)",
                ids.next(),EnhanceSession.current().userId(),"DATASET_ROW_EQUAL",
                roleId+":"+datasetId,System.currentTimeMillis());
    }
}