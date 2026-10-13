package io.dataease.enhance;

import io.dataease.extensions.datasource.dto.DatasetTableFieldDTO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceDatasetRowPolicyTest {
    @Test
    void scopedRowsAreMandatoryBoundToDatasetAndNativeFilterFields() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:row_policy;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc=new JdbcTemplate(source);
        var settings=new EnhanceSettings();
        settings.setTokenSecret("isolated-row-scope-secret-with-sufficient-length");
        settings.setBootstrapPassword("isolated-test-bootstrap-password");
        var encoder=new BCryptPasswordEncoder(4);
        var schema=new EnhanceSchema(jdbc,source,encoder,settings);
        schema.initialize();
        schema.initialize();
        assertEquals(5,jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_schema_version",Integer.class));
        var identity=new EnhanceIdentityService(jdbc,encoder,settings);
        var acl=new EnhanceAcl(jdbc);
        var ids=new EnhanceIds(1);
        var admin=new EnhanceAdminController(jdbc,ids,acl,encoder);
        var policy=new EnhanceDatasetRowPolicy(jdbc,acl,ids);
        policy.setFieldLookup((datasetId,fieldId)->{
            if(datasetId!=77L || fieldId!=5L)
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            var field=new DatasetTableFieldDTO();
            field.setId(fieldId);field.setDatasetGroupId(datasetId);field.setDataeaseName("city");
            return field;
        });
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            long org=Long.parseLong(admin.addOrg(
                    new EnhanceAdminController.NewOrg("A",1L)).get("id"));
            long foreign=Long.parseLong(admin.addOrg(
                    new EnhanceAdminController.NewOrg("B",1L)).get("id"));
            long role=Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(org,"CITY_READER","City Reader")).get("id"));
            long foreignRole=Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(foreign,"OTHER_READER","Other")).get("id"));
            long user=Long.parseLong(admin.addUser(
                    new EnhanceAdminController.NewUser("cityviewer","City Viewer","a-valid-reader-password",org)).get("id"));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(user,role));
            admin.registerResource(new EnhanceAdminController.ResourceRegistration("dataset","77",org));
            admin.replaceResources(role,List.of(new EnhanceAdminController.Grant("dataset","77","read")));
            assertThrows(ResponseStatusException.class,()->policy.replace(foreignRole,77L,
                    List.of(new EnhanceDatasetRowPolicy.Rule(5L,"Tokyo"))));
            assertThrows(ResponseStatusException.class,()->policy.replace(role,77L,
                    List.of(new EnhanceDatasetRowPolicy.Rule(100L,"Tokyo"))));
            assertThrows(ResponseStatusException.class,()->policy.replace(role,77L,
                    List.of(new EnhanceDatasetRowPolicy.Rule(5L,""))));
            assertThrows(ResponseStatusException.class,()->policy.replace(role,77L,List.of()));
            policy.replace(role,77L,List.of(
                    new EnhanceDatasetRowPolicy.Rule(5L,"Tokyo"),
                    new EnhanceDatasetRowPolicy.Rule(5L,"Osaka"),
                    new EnhanceDatasetRowPolicy.Rule(5L,"Tokyo")));
            EnhanceSession.clear();
            EnhanceSession.set(identity.verify(identity.login("cityviewer","a-valid-reader-password").getToken()));
            var predicates=policy.forCurrentUser(77L,user);
            assertEquals(2,predicates.size());
            assertTrue(predicates.stream().allMatch(p->
                    p.getTree().getItems().get(0).getField().getId()==5L));
            assertTrue(predicates.stream().allMatch(p->
                    "eq".equals(p.getTree().getItems().get(0).getTerm())));
            assertThrows(ResponseStatusException.class,()->policy.forCurrentUser(77L,1L));
            assertThrows(ResponseStatusException.class,()->policy.forCurrentUser(78L,user));
            EnhanceSession.clear();
            EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
            admin.replaceResources(role,List.of());
            EnhanceSession.clear();
            EnhanceSession.set(identity.verify(identity.login("cityviewer","a-valid-reader-password").getToken()));
            assertThrows(ResponseStatusException.class,()->policy.forCurrentUser(77L,user));
        } finally {
            EnhanceSession.clear();
        }
    }
}