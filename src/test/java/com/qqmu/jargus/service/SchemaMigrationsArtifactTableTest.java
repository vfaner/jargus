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

/** 旧库升级：新表缺失时补建，重复执行幂等，单行 id=1 可读写。 */
class SchemaMigrationsArtifactTableTest {

    @Test
    void createsMissingTableAndIsIdempotent() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:artifact_migration;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn,
                    new EncodedResource(new ClassPathResource("db/schema-h2.sql"),
                            StandardCharsets.UTF_8));
        }
        jdbc.execute("DROP TABLE update_artifact_dismissal");

        SchemaMigrations migrations = new SchemaMigrations();
        migrations.runAll(ds, SqlDialectAdapter.H2);

        Integer created = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_NAME) = 'UPDATE_ARTIFACT_DISMISSAL'", Integer.class);
        assertEquals(1, created);

        jdbc.update("INSERT INTO update_artifact_dismissal (id, artifact_key, dismissed_at) "
                        + "VALUES (1, ?, ?)", "sha256:abc", Timestamp.valueOf(LocalDateTime.now()));
        assertEquals("sha256:abc", jdbc.queryForObject(
                "SELECT artifact_key FROM update_artifact_dismissal WHERE id = 1", String.class));

        // 第二次执行不能再建表/清数据
        migrations.runAll(ds, SqlDialectAdapter.H2);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM update_artifact_dismissal", Integer.class));
    }
}
