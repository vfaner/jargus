package com.qqmu.jargus.service;

import com.qqmu.jargus.datasource.SqlDialectAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 旧库升级：sso_config 缺失时补建，幂等，单行 id=1 可读写。 */
class SchemaMigrationsSsoTableTest {

    @Test
    void createsMissingTableAndIsIdempotent() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sso_migration;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/schema-h2.sql"),
                            StandardCharsets.UTF_8));
        }
        jdbc.execute("DROP TABLE sso_config");

        SchemaMigrations migrations = new SchemaMigrations();
        migrations.runAll(ds, SqlDialectAdapter.H2);

        Integer created = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_NAME) = 'SSO_CONFIG'", Integer.class);
        assertEquals(1, created);

        jdbc.update("INSERT INTO sso_config (id, is_enabled, auth_mode, admin_users, updated_at) "
                        + "VALUES (1, TRUE, 'TICKET', ?, ?)",
                "alice,bob", Timestamp.valueOf(LocalDateTime.now()));
        assertEquals("alice,bob", jdbc.queryForObject(
                "SELECT admin_users FROM sso_config WHERE id = 1", String.class));

        migrations.runAll(ds, SqlDialectAdapter.H2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sso_config", Integer.class));
    }
}
