package io.dataease.enhance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceResourceOwnershipTest {
    @Test
    void grantsCannotCrossOrganizationsOrReferenceUnknownResources() {
        JdbcDataSource datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:ownership;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        EnhanceSettings settings = new EnhanceSettings();
        settings.setBootstrapPassword("isolated-test-bootstrap-password");
        settings.setTokenSecret("isolated-test-secret-32-character-minimum-value");
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc,datasource,bcrypt,settings).initialize();
        EnhanceIdentityService identity = new EnhanceIdentityService(jdbc,bcrypt,settings);
        EnhanceIds ids = new EnhanceIds(1);
        EnhanceAcl acl = new EnhanceAcl(jdbc);
        EnhanceAdminController admin = new EnhanceAdminController(jdbc,ids,acl,bcrypt);
        long alphaOrg;
        long betaOrg;
        long alphaRole;
        long betaRole;
        long alphaUser;
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            alphaOrg = Long.parseLong(admin.addOrg(
                    new EnhanceAdminController.NewOrg("Alpha",1L)).get("id"));
            betaOrg = Long.parseLong(admin.addOrg(
                    new EnhanceAdminController.NewOrg("Beta",1L)).get("id"));
            alphaRole = Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(alphaOrg,"ALPHA_READER","Alpha reader")).get("id"));
            betaRole = Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(betaOrg,"BETA_READER","Beta reader")).get("id"));
            alphaUser = Long.parseLong(admin.addUser(
                    new EnhanceAdminController.NewUser("alpha","Alpha","isolated-alpha-password",alphaOrg)).get("id"));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(alphaUser,alphaRole));
            admin.registerResource(new EnhanceAdminController.ResourceRegistration(
                    "dataset","100",alphaOrg));
            assertThrows(ResponseStatusException.class,() ->
                    admin.registerResource(new EnhanceAdminController.ResourceRegistration(
                            "dataset","100",betaOrg)));
            assertThrows(ResponseStatusException.class,() ->
                    admin.replaceResources(betaRole,List.of(
                            new EnhanceAdminController.Grant("dataset","100","read"))));
            assertThrows(ResponseStatusException.class,() ->
                    admin.replaceResources(alphaRole,List.of(
                            new EnhanceAdminController.Grant("dataset","missing","read"))));
            assertEquals(0,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_resource_acl WHERE role_id=?",Integer.class,alphaRole));
            admin.replaceResources(alphaRole,List.of(
                    new EnhanceAdminController.Grant("dataset","100","read")));
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(identity.login("alpha","isolated-alpha-password").getToken()));
        try {
            assertTrue(acl.permitted("dataset","100","read",alphaOrg));
            assertFalse(acl.permitted("dataset","100","manage",alphaOrg));
            assertFalse(acl.permitted("dataset","100","read",betaOrg));
            assertFalse(acl.permitted("dataset","unknown","read",alphaOrg));
        } finally {
            EnhanceSession.clear();
        }
    }
}