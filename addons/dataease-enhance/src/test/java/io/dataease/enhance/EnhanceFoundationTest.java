package io.dataease.enhance;

import io.dataease.auth.vo.TokenVO;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceFoundationTest {
    private JdbcTemplate jdbc;
    private EnhanceIdentityService identity;
    private EnhanceAcl acl;
    private EnhanceIds ids;
    private final EnhanceSettings settings=new EnhanceSettings();

    @BeforeEach
    void setUp() {
        JdbcDataSource ds=new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:deext"+UUID.randomUUID().toString().replace("-","")+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);
        settings.setTokenSecret("local-test-only-32-character-signing-secret-123456");
        settings.setBootstrapPassword("bootstrap-password-12345");
        var bcrypt=new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc,ds,bcrypt,settings).initialize();
        identity=new EnhanceIdentityService(jdbc,bcrypt,settings);
        acl=new EnhanceAcl(jdbc);
        ids=new EnhanceIds(1);
    }

    @Test
    void loginWorksAndTokenVersionRevokesSessions() {
        TokenVO issued=identity.login("admin","bootstrap-password-12345");
        assertEquals(1L,identity.verify(issued.getToken()).userId());
        identity.logoutEverywhere(1L);
        assertThrows(ResponseStatusException.class,()->identity.verify(issued.getToken()));
    }

    @Test
    void wrongPasswordLockedAfterRepeatedFailures() {
        settings.setMaxLoginFailures(2);
        assertThrows(ResponseStatusException.class,()->identity.login("admin","wrong"));
        assertThrows(ResponseStatusException.class,()->identity.login("admin","wrong"));
        assertThrows(ResponseStatusException.class,()->identity.login("admin","bootstrap-password-12345"));
    }

    @Test
    void nonmemberCannotAssumeAnotherOrganization() {
        long newOrg=ids.next();
        jdbc.update("INSERT INTO de_ext_org (id,parent_id,name,enabled) VALUES (?,?,?,1)",newOrg,1L,"second");
        assertThrows(ResponseStatusException.class,()->identity.issue(1L,newOrg));
    }

    @Test
    void resourceGrantRequiresSameOrganizationMembership() {
        long userId=ids.next();
        long roleId=ids.next();
        jdbc.update("INSERT INTO de_ext_user (id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                userId,"tester","Tester","bcrypt-unused",1,0,0L,0,System.currentTimeMillis());
        jdbc.update("INSERT INTO de_ext_membership (user_id,org_id) VALUES (?,?)",userId,1L);
        jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,0)",roleId,1L,"TEST_ROLE","Tester");
        jdbc.update("INSERT INTO de_ext_user_role (user_id,role_id) VALUES (?,?)",userId,roleId);
        jdbc.update("INSERT INTO de_ext_resource_acl (role_id,resource_type,resource_id,action) VALUES (?,?,?,?)",
                roleId,"dashboard","42","read");
        try {
            EnhanceSession.set(new EnhanceSession.Identity(userId,1L,"tester",false));
            assertTrue(acl.permitted("dashboard","42","read",1L));
            assertFalse(acl.permitted("dashboard","42","manage",1L));
            assertFalse(acl.permitted("dashboard","42","read",999L));
        } finally {
            EnhanceSession.clear();
        }
        assertNull(EnhanceSession.peek());
    }

    @Test
    void bootstrapDoesNotResetExistingAdminPassword() {
        var original=jdbc.queryForObject("SELECT password_hash FROM de_ext_user WHERE id=1",String.class);
        var bcrypt=new BCryptPasswordEncoder(4);
        settings.setBootstrapPassword("different-password-12345");
        new EnhanceSchema(jdbc,jdbc.getDataSource(),bcrypt,settings).initialize();
        assertEquals(original,jdbc.queryForObject("SELECT password_hash FROM de_ext_user WHERE id=1",String.class));
    }
}