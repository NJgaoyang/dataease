package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EnhanceSettings.class)
public class EnhanceConfiguration {
    @Bean
    public BCryptPasswordEncoder enhancePasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public EnhanceIds enhanceIds(EnhanceSettings settings) {
        return new EnhanceIds(settings.getNodeId());
    }
}