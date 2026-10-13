package io.dataease.enhance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceOrgMemberServerTest {
    @Test
    void respectsOrganizationBoundariesAndProtectsLastAdmin() {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:org_member;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        EnhanceSettings settings=new EnhanceSettings();
        settings.setTokenSecret("isolated-test-signing-secret-length-over-32-chars");
        settings.setBootstrapPassword("isolated-root-password-1234");
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc,source,encoder,settings).initialize();
        EnhanceIds ids=new EnhanceIds(1);
        EnhanceIdentityService identity=new EnhanceIdentityService(jdbc,encoder,settings);
        EnhanceAcl acl=new EnhanceAcl(jdbc);
        EnhanceAdminController admin=new EnhanceAdminController(jdbc,ids,acl,encoder);
        EnhanceOrgGovernanceController governance=new EnhanceOrgGovernanceController(jdbc,acl,ids);
        EnhanceOrgMemberServer server=new EnhanceOrgMemberServer(jdbc,acl,governance,ids);
        long org;
        long roleAdmin;
        long viewer;
        long manager;
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            org=Long.parseLong(admin.addOrg(new EnhanceAdminController.NewOrg("Regional",1L)).get("id"));
            roleAdmin=jdbc.queryForObject(
                    "SELECT id FROM de_ext_role WHERE org_id=? AND code='ORG_ADMIN'",Long.class,org);
            viewer=Long.parseLong(admin.addUser(
                    new EnhanceAdminController.NewUser("reader123","Reader","isolated-reader-password",1L)).get("id"));
            manager=Long.parseLong(admin.addUser(
                    new EnhanceAdminController.NewUser("manager123","Manager","isolated-manager-password",org)).get("id"));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(manager,roleAdmin));
            // Only super administrators may enumerate candidates from all organizations.
            assertTrue(server.candidates(Map.of("keyword","reader")).stream().anyMatch(
                    user -> String.valueOf(viewer).equals(user.get("id"))));
            assertThrows(ResponseStatusException.class,()->server.add(
                    new EnhanceOrgMemberServer.MembersAdd(org,List.of(viewer),List.of(1L))));
            assertEquals(0,jdbc.queryForObject(
                    "SELECT COUNT(*) FROM de_ext_membership WHERE user_id=? AND org_id=?",
                    Integer.class,viewer,org));
            server.add(new EnhanceOrgMemberServer.MembersAdd(org,List.of(viewer),List.of()));
            assertEquals(2L,server.page(new EnhanceOrgMemberServer.MembersInput(
                    org,1,null,10,null,null)).get("total"));
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(identity.login("manager123","isolated-manager-password").getToken()));
        try {
            assertTrue(acl.organizationAdmin(manager,org));
            assertThrows(ResponseStatusException.class,()->server.candidates(Map.of()));
            assertThrows(ResponseStatusException.class,()->server.page(
                    new EnhanceOrgMemberServer.MembersInput(1L,1,null,10,null,null)));
            assertThrows(ResponseStatusException.class,()->server.remove(manager,org));
            assertThrows(ResponseStatusException.class,()->server.switchRole(
                    new EnhanceOrgMemberServer.RoleSwitch(org,viewer,List.of(1L))));
            assertEquals(2L,server.page(new EnhanceOrgMemberServer.MembersInput(
                    org,1,null,10,null,null)).get("total"));
            server.remove(viewer,org);
            assertFalse(acl.member(viewer,org));
        } finally {
            EnhanceSession.clear();
        }
    }
}