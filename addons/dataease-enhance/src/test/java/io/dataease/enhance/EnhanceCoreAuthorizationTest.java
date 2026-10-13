package io.dataease.enhance;

import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.constant.AuthEnum;
import io.dataease.constant.BusiResourceEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceCoreAuthorizationTest {
    @Test
    void mapsNativeResourceChecksToVerifiedOrgGrantsAndFailsClosed() {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:core_auth;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        EnhanceSettings settings=new EnhanceSettings();
        settings.setTokenSecret("isolated-test-secret-value-longer-than-32-characters");
        settings.setBootstrapPassword("isolated-root-password-1234");
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc,source,encoder,settings).initialize();
        EnhanceIdentityService identity=new EnhanceIdentityService(jdbc,encoder,settings);
        EnhanceAcl acl=new EnhanceAcl(jdbc);
        EnhanceIds ids=new EnhanceIds(1);
        EnhanceAdminController admin=new EnhanceAdminController(jdbc,ids,acl,encoder);
        EnhanceCoreAuthorization permission=new EnhanceCoreAuthorization(acl);
        BusiPerCheckDTO requested = new BusiPerCheckDTO(42L,BusiResourceEnum.DATASET,AuthEnum.READ);
        assertFalse(permission.check(requested));
        EnhanceSession.set(identity.verify(identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            long roleId=Long.parseLong(admin.addRole(
                    new EnhanceAdminController.NewRole(1L,"VIEWER","Viewer")).get("id"));
            long uid=Long.parseLong(admin.addUser(
                    new EnhanceAdminController.NewUser("dataset_user","Dataset Reader","isolated-reader-password",1L)).get("id"));
            admin.assignRole(new EnhanceAdminController.RoleAssignment(uid,roleId));
            admin.registerResource(new EnhanceAdminController.ResourceRegistration("dataset","42",1L));
            admin.replaceResources(roleId,List.of(new EnhanceAdminController.Grant("dataset","42","read")));
            assertTrue(permission.check(requested));
        } finally {
            EnhanceSession.clear();
        }
        EnhanceSession.set(identity.verify(identity.login("dataset_user","isolated-reader-password").getToken()));
        try {
            assertTrue(permission.check(requested));
            assertFalse(permission.check(new BusiPerCheckDTO(42L,BusiResourceEnum.DATASET,AuthEnum.MANAGE)));
            assertFalse(permission.check(new BusiPerCheckDTO(43L,BusiResourceEnum.DATASET,AuthEnum.READ)));
            assertFalse(permission.check(new BusiPerCheckDTO(42L,BusiResourceEnum.MENU,AuthEnum.READ)));
            assertFalse(permission.check(null));
        } finally {
            EnhanceSession.clear();
        }
        assertFalse(permission.check(requested));
    }
}