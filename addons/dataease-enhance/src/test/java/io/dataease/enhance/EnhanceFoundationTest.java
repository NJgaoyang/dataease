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

    @Test
    void lockedAccountInvalidatesEvenUnexpiredToken() {
        TokenVO token = identity.login("admin", "bootstrap-password-12345");
        jdbc.update("UPDATE de_ext_user SET locked_until=? WHERE id=1",
                System.currentTimeMillis() + 60000L);
        assertThrows(ResponseStatusException.class, () -> identity.verify(token.getToken()));
    }

    @Test
    void organizationAdminCanManageOnlyOwnOrganization() {
        var encoder = new BCryptPasswordEncoder(4);
        long orgA = ids.next();
        long orgB = ids.next();
        jdbc.update("INSERT INTO de_ext_org(id,parent_id,name,enabled) VALUES (?,?,?,1)",orgA,1L,"A");
        jdbc.update("INSERT INTO de_ext_org(id,parent_id,name,enabled) VALUES (?,?,?,1)",orgB,1L,"B");
        long orgAdminRole = ids.next();
        jdbc.update("INSERT INTO de_ext_role(id,org_id,code,name,system_role) VALUES (?,?,?,?,1)",
                orgAdminRole, orgA, "ORG_ADMIN", "组织管理员");
        long adminId = ids.next();
        jdbc.update("INSERT INTO de_ext_user(id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                adminId,"org_admin","Org Admin",encoder.encode("a-strong-test-password"),1,0,0L,0,System.currentTimeMillis());
        jdbc.update("INSERT INTO de_ext_membership(user_id,org_id) VALUES (?,?)",adminId,orgA);
        jdbc.update("INSERT INTO de_ext_user_role(user_id,role_id) VALUES (?,?)",adminId,orgAdminRole);

        var controller = new EnhanceOrgGovernanceController(jdbc,acl,ids);
        var token = identity.login("org_admin","a-strong-test-password");
        EnhanceSession.set(identity.verify(token.getToken()));
        try {
            assertTrue(acl.organizationAdmin(adminId,orgA));
            assertFalse(acl.organizationAdmin(adminId,orgB));
            assertThrows(ResponseStatusException.class, () -> controller.createRole(
                    new EnhanceOrgGovernanceController.RoleCreate(orgB,"Forbidden")));
            long roleId = Long.parseLong(controller.createRole(
                    new EnhanceOrgGovernanceController.RoleCreate(orgA,"Custom reader")).get("id"));
            assertEquals(1,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_role WHERE id=? AND org_id=?",Integer.class,roleId,orgA));
            assertThrows(ResponseStatusException.class, () -> controller.replaceMemberRoles(
                    new EnhanceOrgGovernanceController.RoleChange(orgA,adminId,java.util.List.of(1L))));
            assertThrows(ResponseStatusException.class, () -> controller.replaceMemberRoles(
                    new EnhanceOrgGovernanceController.RoleChange(orgB,adminId,java.util.List.of())));
            // No impersonation: request-scoped user ID always comes from a validated token.
            assertEquals(adminId,EnhanceSession.current().userId());
        } finally {
            EnhanceSession.clear();
        }
    }

    @Test
    void nonAdminCannotListOthersEvenWhenMember() {
        long uid = ids.next();
        jdbc.update("INSERT INTO de_ext_user(id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                uid,"ordinary","Ordinary","bcrypt-unused",1,0,0L,0,System.currentTimeMillis());
        jdbc.update("INSERT INTO de_ext_membership(user_id,org_id) VALUES (?,?)",uid,1L);
        EnhanceSession.set(new EnhanceSession.Identity(uid,1L,"ordinary",false));
        try {
            assertThrows(ResponseStatusException.class,()->acl.requireOrgAdmin(1L));
            assertFalse(acl.permitted("dataset","123","read",1L));
            assertFalse(acl.menuAllowed("SYSUSER",1L));
        } finally {
            EnhanceSession.clear();
        }
    }

    @Test
    void adminMayConfigureOrgAdminMenuWithoutChangingGlobalSysAdmin() {
        var bcrypt=new BCryptPasswordEncoder(4);
        var adminController=new EnhanceAdminController(jdbc,ids,acl,bcrypt);
        EnhanceSession.set(identity.verify(identity.login("admin","bootstrap-password-12345").getToken()));
        try {
            adminController.replaceMenus(2L,java.util.List.of("DATA"));
            assertEquals(1,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_menu_acl WHERE role_id=2 AND menu_code='DATA'",Integer.class));
            assertThrows(ResponseStatusException.class,()->
                adminController.replaceMenus(1L,java.util.List.of("ANY")));
        } finally {
            EnhanceSession.clear();
        }
    }

    @Test
    void apiFilterBlocksAnonymousAndNonAdminCoreQueries() throws Exception {
        var filter = new EnhanceTokenFilter(identity);
        var anonymous = new org.springframework.mock.web.MockHttpServletRequest("POST","/de2api/chart/data");
        var noToken = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(anonymous,noToken,new org.springframework.mock.web.MockFilterChain());
        assertEquals(401,noToken.getStatus());
        assertNull(EnhanceSession.peek());

        long uid=ids.next();
        var bcrypt=new BCryptPasswordEncoder(4);
        jdbc.update("INSERT INTO de_ext_user (id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                uid,"viewer","Viewer",bcrypt.encode("viewer-test-password-123"),1,0,0L,0,System.currentTimeMillis());
        jdbc.update("INSERT INTO de_ext_membership (user_id,org_id) VALUES (?,?)",uid,1L);
        String normalToken=identity.login("viewer","viewer-test-password-123").getToken();

        var noCoreAccess = new org.springframework.mock.web.MockHttpServletRequest("POST","/de2api/chart/data");
        noCoreAccess.addHeader("X-DE-TOKEN",normalToken);
        var denied = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(noCoreAccess,denied,new org.springframework.mock.web.MockFilterChain());
        assertEquals(403,denied.getStatus());
        assertNull(EnhanceSession.peek());

        var safeSettings = new org.springframework.mock.web.MockHttpServletRequest(
                "GET","/de2api/sysParameter/defaultSettings");
        safeSettings.addHeader("X-DE-TOKEN",normalToken);
        var permitted = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(safeSettings,permitted,new org.springframework.mock.web.MockFilterChain());
        assertEquals(200,permitted.getStatus());
        assertNull(EnhanceSession.peek());

        var publicKey = new org.springframework.mock.web.MockHttpServletRequest("GET","/de2api/dekey");
        var publicResult = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(publicKey,publicResult,new org.springframework.mock.web.MockFilterChain());
        assertEquals(200,publicResult.getStatus());
        assertNull(EnhanceSession.peek());
    }
    @Test
    void organizationAdapterCreatesAndRefusesOccupiedDeletion() {
        var admin = new EnhanceAdminController(jdbc,ids,acl,new BCryptPasswordEncoder(4));
        var adapter = new EnhanceOrgCompatServer(jdbc,acl,admin);
        EnhanceSession.set(identity.verify(identity.login("admin","bootstrap-password-12345").getToken()));
        try {
            var request = new io.dataease.api.permissions.org.dto.OrgCreator();
            request.setName("Operations");
            request.setPid(1L);
            Long oid = adapter.create(request);
            assertFalse(adapter.resourceExist(oid));
            admin.addUser(new EnhanceAdminController.NewUser("worker","Worker","long-password-1234",oid));
            assertTrue(adapter.resourceExist(oid));
            assertThrows(ResponseStatusException.class, ()->admin.deleteOrg(oid));
        } finally { EnhanceSession.clear(); }
    }
}