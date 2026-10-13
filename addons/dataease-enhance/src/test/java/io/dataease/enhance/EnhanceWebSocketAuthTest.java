package io.dataease.enhance;

import io.dataease.websocket.WebSocketAuthService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class EnhanceWebSocketAuthTest {
    @Test
    void validatesAndRevokesSocketIdentityWithTheSameJwtPolicy() {
        JdbcDataSource source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:websocket_auth;MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        EnhanceSettings settings=new EnhanceSettings();
        settings.setTokenSecret("isolated-test-websocket-signing-key-over-32-characters");
        settings.setBootstrapPassword("isolated-admin-strong-password");
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(4);
        new EnhanceSchema(jdbc,source,encoder,settings).initialize();
        EnhanceIdentityService identity=new EnhanceIdentityService(jdbc,encoder,settings);
        WebSocketAuthService sockets=new EnhanceWebSocketConfiguration()
                .enhanceWebSocketAuthService(identity);
        String adminToken=identity.login("admin",settings.getBootstrapPassword()).getToken();
        assertEquals(1L,sockets.authenticate(adminToken));
        assertNull(sockets.authenticate("invalid.token"));
        assertNull(sockets.authenticate(null));
        identity.logoutEverywhere(1L);
        assertNull(sockets.authenticate(adminToken));
    }
}