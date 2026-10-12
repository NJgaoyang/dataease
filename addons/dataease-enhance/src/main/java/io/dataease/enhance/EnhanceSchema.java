package io.dataease.enhance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

/**
 * Version 1 is additive and restricted to de_ext_* tables.
 * Existing user/org state is never overwritten on restart.
 */
@Component
@ConditionalOnProperty(prefix = "dataease.enhance", name = "enabled", havingValue = "true")
public class EnhanceSchema {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final BCryptPasswordEncoder bcrypt;
    private final EnhanceSettings settings;

    public EnhanceSchema(JdbcTemplate jdbc, DataSource dataSource, BCryptPasswordEncoder bcrypt,
                         EnhanceSettings settings) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.bcrypt = bcrypt;
        this.settings = settings;
    }

    @jakarta.annotation.PostConstruct
    public void initialize() {
        if (settings.getTokenSecret() == null || settings.getTokenSecret().length() < 32) {
            throw new IllegalStateException("dataease.enhance.token-secret must be at least 32 characters");
        }
        if (settings.getSessionMinutes() < 5 || settings.getSessionMinutes() > 1440) {
            throw new IllegalStateException("session-minutes must be in 5..1440");
        }
        if (settings.getMaxLoginFailures() < 1 || settings.getMaxLoginFailures() > 100 ||
                settings.getLockMinutes() < 1 || settings.getLockMinutes() > 1440) {
            throw new IllegalStateException("Invalid login failure/lockout policy");
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS de_ext_schema_version (" +
                "version VARCHAR(30) NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL)");
        Integer migrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version WHERE version=?",Integer.class,"V001");
        if (migrated == null || migrated == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/enhance/V001__identity_org_acl.sql"))
                    .execute(dataSource);
            jdbc.update("INSERT INTO de_ext_schema_version (version,applied_at) VALUES (?,?)",
                    "V001",System.currentTimeMillis());
        }
        // Bootstrap only an entirely fresh enhance store. No implicit or hard-coded password.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM de_ext_user", Integer.class);
        if (count != null && count == 0) {
            if (settings.getBootstrapPassword() == null || settings.getBootstrapPassword().length() < 12) {
                throw new IllegalStateException("Set dataease.enhance.bootstrap-password (12+ chars) for first activation");
            }
            jdbc.update("INSERT INTO de_ext_user (id,account,display_name,password_hash,enabled,failed_attempts,locked_until,token_version,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    1L, "admin", "超级管理员", bcrypt.encode(settings.getBootstrapPassword()), 1, 0, 0L, 0,
                    System.currentTimeMillis());
            jdbc.update("INSERT INTO de_ext_org (id,parent_id,name,enabled) VALUES (?,?,?,?)", 1L, null, "默认组织", 1);
            jdbc.update("INSERT INTO de_ext_membership (user_id,org_id) VALUES (?,?)", 1L, 1L);
            jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,?)",
                    1L, 1L, "SYS_ADMIN", "系统管理员", 1);
            jdbc.update("INSERT INTO de_ext_user_role (user_id,role_id) VALUES (?,?)", 1L, 1L);
            jdbc.update("INSERT INTO de_ext_role (id,org_id,code,name,system_role) VALUES (?,?,?,?,?)",
                    2L, 1L, "ORG_ADMIN", "组织管理员", 1);
        }
    }
}