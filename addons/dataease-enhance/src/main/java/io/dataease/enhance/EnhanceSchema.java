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
        Integer userProfileMigrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version WHERE version=?",Integer.class,"V002");
        if (userProfileMigrated == null || userProfileMigrated == 0) {
            applyUserProfileMigration();
            jdbc.update("INSERT INTO de_ext_schema_version (version,applied_at) VALUES (?,?)",
                    "V002",System.currentTimeMillis());
        }
        Integer ownershipMigrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version WHERE version=?",Integer.class,"V003");
        if (ownershipMigrated == null || ownershipMigrated == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/enhance/V003__resource_ownership.sql"))
                    .execute(dataSource);
            jdbc.update("INSERT INTO de_ext_schema_version (version,applied_at) VALUES (?,?)",
                    "V003",System.currentTimeMillis());
        }
        Integer columnsMigrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version WHERE version=?",Integer.class,"V004");
        if (columnsMigrated == null || columnsMigrated == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/enhance/V004__dataset_column_allow.sql"))
                    .execute(dataSource);
            jdbc.update("INSERT INTO de_ext_schema_version (version,applied_at) VALUES (?,?)",
                    "V004",System.currentTimeMillis());
        }
        Integer rowScopesMigrated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM de_ext_schema_version WHERE version=?",Integer.class,"V005");
        if (rowScopesMigrated == null || rowScopesMigrated == 0) {
            new ResourceDatabasePopulator(new ClassPathResource("db/enhance/V005__dataset_row_equal.sql"))
                    .execute(dataSource);
            jdbc.update("INSERT INTO de_ext_schema_version (version,applied_at) VALUES (?,?)",
                    "V005",System.currentTimeMillis());
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
    /**
     * V002 is idempotent per column, not merely per migration version.
     * A crash halfway through ALTER TABLE can safely resume on restart.
     * The resource SQL describes the same additive schema for review.
     */
    private void applyUserProfileMigration() {
        var metadata = jdbc.queryForRowSet("SELECT * FROM de_ext_user WHERE 1=0").getMetaData();
        var existing = new java.util.HashSet<String>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            existing.add(metadata.getColumnName(i).toLowerCase(java.util.Locale.ROOT));
        }
        var required = new java.util.LinkedHashMap<String,String>();
        required.put("email","VARCHAR(255)");
        required.put("phone_prefix","VARCHAR(16)");
        required.put("phone","VARCHAR(64)");
        required.put("deleted","INTEGER NOT NULL DEFAULT 0");
        for (var column : required.entrySet()) {
            if (!existing.contains(column.getKey())) {
                jdbc.execute("ALTER TABLE de_ext_user ADD COLUMN " +
                        column.getKey() + " " + column.getValue());
            }
        }
    }

}