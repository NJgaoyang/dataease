package io.dataease.enhance;

import io.dataease.websocket.WebSocketAuthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceWebSocketConfiguration {
    @Bean
    public WebSocketAuthService enhanceWebSocketAuthService(EnhanceIdentityService identity) {
        return token -> {
            try { return identity.verify(token).userId(); }
            catch (RuntimeException e) { return null; }
        };
    }
}