package io.dataease.enhance;

import io.dataease.api.permissions.user.dto.UserCreator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceUserFlowTest {
    @Test
    void createsOneTimeCredentialsAndRevokesDeletedUser() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:enhance_user_flow;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        EnhanceSettings settings = new EnhanceSettings();
        settings.setTokenSecret("isolated-test-signature-secret-not-used-in-production");
        settings.setBootstrapPassword("isolated-test-bootstrap-password");
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
        EnhanceSchema schema = new EnhanceSchema(jdbc,source,bcrypt,settings);
        schema.initialize();
        schema.initialize();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_schema_version",Integer.class));
        // Reproduce a partially completed ALTER TABLE after a process interruption.
        jdbc.execute("ALTER TABLE de_ext_user DROP COLUMN phone");
        jdbc.update("DELETE FROM de_ext_schema_version WHERE version='V002'");
        schema.initialize();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_schema_version",Integer.class));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='DE_EXT_USER' AND COLUMN_NAME='PHONE'",
                Integer.class));
        EnhanceIdentityService identity = new EnhanceIdentityService(jdbc,bcrypt,settings);
        EnhanceAcl acl = new EnhanceAcl(jdbc);
        EnhanceIds ids = new EnhanceIds(1);
        EnhanceAdminController admin = new EnhanceAdminController(jdbc,ids,acl,bcrypt);
        EnhanceOrgGovernanceController governance = new EnhanceOrgGovernanceController(jdbc,acl,ids);
        EnhanceUserServer users = new EnhanceUserServer(identity,jdbc,acl,admin,governance,bcrypt);
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            UserCreator request = new UserCreator();
            request.setAccount("test-person");
            request.setName("Test Person");
            request.setDefaultOid(1L);
            request.setEmail("person@example.invalid");
            MockHttpServletResponse response = new MockHttpServletResponse();
            String oneTime = users.create(request,response);
            assertTrue(oneTime.startsWith("DE_ENHANCE_ONETIME:"));
            assertEquals("no-store, no-cache, private",response.getHeader("Cache-Control"));
            String issuedPassword = oneTime.substring("DE_ENHANCE_ONETIME:".length());
            assertTrue(issuedPassword.length() >= 32);
            String token = identity.login("test-person",issuedPassword).getToken();
            long uid = identity.verify(token).userId();
            assertNotEquals(1L,uid);
            assertEquals("person@example.invalid",
                    jdbc.queryForObject("SELECT email FROM de_ext_user WHERE id=?",String.class,uid));
            assertNotEquals(issuedPassword,
                    jdbc.queryForObject("SELECT password_hash FROM de_ext_user WHERE id=?",String.class,uid));
            admin.deleteUser(uid);
            assertThrows(ResponseStatusException.class,() -> identity.verify(token));
            assertThrows(ResponseStatusException.class,() -> identity.login("test-person",issuedPassword));
            assertEquals(1,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_user WHERE id=? AND deleted=1",Integer.class,uid));
        } finally {
            EnhanceSession.clear();
        }
    }
}