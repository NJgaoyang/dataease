package io.dataease.enhance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceDatasetColumnPolicyTest {
    @Test
    void onlyWhitelistedFieldsFromSameOrganizationAreAllowed() {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:enhance_column_allow;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        EnhanceSettings settings=new EnhanceSettings();
        settings.setTokenSecret("isolated-test-column-policy-secret-over-32-characters");
        settings.setBootstrapPassword("isolated-column-root-password");
        BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder(4);
        EnhanceSchema schema=new EnhanceSchema(jdbc,source,bcrypt,settings);
        schema.initialize();
        schema.initialize();
        assertEquals(4,jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version",Integer.class));
        EnhanceIdentityService identity=new EnhanceIdentityService(jdbc,bcrypt,settings);
        EnhanceAcl acl=new EnhanceAcl(jdbc);
        EnhanceIds ids=new EnhanceIds(1);
        EnhanceAdminController admin=new EnhanceAdminController(jdbc,ids,acl,bcrypt);
        EnhanceDatasetColumnPolicy policy=new EnhanceDatasetColumnPolicy(jdbc,acl,ids);

        long firstOrg;
        long otherOrg;
        long role;
        long foreignRole;
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            firstOrg=Long.parseLong(admin.addOrg(new EnhanceAdminController.NewOrg("First",1L)).get("id"));
            otherOrg=Long.parseLong(admin.addOrg(new EnhanceAdminController.NewOrg("Other",1L)).get("id"));
            role=Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(firstOrg,"FIELD_READER","Field reader")).get("id"));
            foreignRole=Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(otherOrg,"FOREIGN_ROLE","Foreign reader")).get("id"));
            long uid=Long.parseLong(admin.addUser(new EnhanceAdminController.NewUser(
                    "columnuser","Column User","isolated-column-user-password",firstOrg)).get("id"));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(uid,role));
            admin.registerResource(new EnhanceAdminController.ResourceRegistration("dataset","551",firstOrg));
            admin.replaceResources(role,List.of(new EnhanceAdminController.Grant("dataset","551","read")));
            assertThrows(ResponseStatusException.class,()->policy.replaceColumns(foreignRole,551L,List.of(11L)));
            assertThrows(ResponseStatusException.class,()->policy.replaceColumns(role,991L,List.of(11L)));
            policy.replaceColumns(role,551L,List.of(11L,22L,22L));
            assertEquals(2,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_dataset_column_allow WHERE role_id=?",Integer.class,role));
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(
                identity.login("columnuser","isolated-column-user-password").getToken()));
        try {
            assertEquals(Set.of(11L,22L),policy.allowedColumns(551L));
            assertThrows(ResponseStatusException.class,()->policy.allowedColumns(991L));
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            policy.replaceColumns(role,551L,List.of());
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(
                identity.login("columnuser","isolated-column-user-password").getToken()));
        try {
            assertTrue(policy.allowedColumns(551L).isEmpty());
        } finally {
            EnhanceSession.clear();
        }
    }
}