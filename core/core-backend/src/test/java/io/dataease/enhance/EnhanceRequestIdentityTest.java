package io.dataease.enhance;

import io.dataease.permission.util.V3UserUtil;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceRequestIdentityTest {
    @Test
    void installsVerifiedIdentityForRequestAndRestoresAfterward() throws Exception {
        var dbSource = new JdbcDataSource();
        dbSource.setURL("jdbc:h2:mem:native_scope_request;MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(dbSource);
        var settings = new EnhanceSettings();
        settings.setTokenSecret("isolated-test-request-scope-secret-over-32-bytes");
        settings.setBootstrapPassword("isolated-bootstrap-test-password");
        var bcrypt = new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc, dbSource, bcrypt, settings).initialize();
        var ids = new EnhanceIds(1);
        var identity = new EnhanceIdentityService(jdbc,bcrypt,settings);
        var acl = new EnhanceAcl(jdbc);
        long uid;
        EnhanceSession.set(identity.verify(
                identity.login("admin",settings.getBootstrapPassword()).getToken()));
        try {
            uid = Long.parseLong(new EnhanceAdminController(jdbc,ids,acl,bcrypt).addUser(
                    new EnhanceAdminController.NewUser("native_user","Native User",
                            "isolated-test-user-password",1L)).get("id"));
        } finally {
            EnhanceSession.clear();
        }
        String token = identity.login("native_user","isolated-test-user-password").getToken();
        var request = new MockHttpServletRequest("GET","/de2api/user/info");
        request.addHeader("X-DE-TOKEN",token);
        V3UserUtil.setUid(1L);
        try {
            var filter = new EnhanceTokenFilter(identity);
            filter.setNativeIdentity(new EnhanceNativeIdentityAdapter());
            var response = new MockHttpServletResponse();
            var seenNative = new AtomicReference<Long>();
            var seenSession = new AtomicReference<Long>();
            filter.doFilter(request,response,(req,res) -> {
                seenNative.set(V3UserUtil.getUid());
                seenSession.set(EnhanceSession.current().userId());
            });
            assertEquals(200,response.getStatus());
            assertEquals(uid,seenNative.get());
            assertEquals(uid,seenSession.get());
            assertEquals(1L,V3UserUtil.getUid());
            assertNull(EnhanceSession.peek());

            var missingBridge = new EnhanceTokenFilter(identity);
            var denied = new MockHttpServletResponse();
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            missingBridge.doFilter(newAuthenticatedRequest(token),denied,
                    (req,res) -> reached.set(true));
            assertEquals(503,denied.getStatus());
            assertFalse(reached.get());
            assertNull(EnhanceSession.peek());
        } finally {
            V3UserUtil.setUid(null);
            EnhanceSession.clear();
        }
    }

    private static MockHttpServletRequest newAuthenticatedRequest(String token) {
        var request = new MockHttpServletRequest("GET","/de2api/user/info");
        request.addHeader("X-DE-TOKEN",token);
        return request;
    }
}