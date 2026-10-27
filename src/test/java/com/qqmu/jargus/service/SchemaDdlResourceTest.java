package com.qqmu.jargus.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 五个方言脚本都必须带新表，且使用各家族可执行的 DDL 语法。 */
class SchemaDdlResourceTest {

    @Test
    void everyDialectScriptContainsArtifactDismissalTable() throws Exception {
        for (String script : new String[]{
                "db/schema-h2.sql", "db/schema-mysql.sql", "db/schema-postgresql.sql",
                "db/schema-oracle.sql", "db/schema-sqlserver.sql"}) {
            assertTrue(read(script).toLowerCase().contains("update_artifact_dismissal"),
                    script + " 缺少 update_artifact_dismissal");
        }
    }

    @Test
    void h2MysqlPostgresUseIfNotExists() throws Exception {
        for (String script : new String[]{
                "db/schema-h2.sql", "db/schema-mysql.sql", "db/schema-postgresql.sql"}) {
            assertTrue(read(script).contains("CREATE TABLE IF NOT EXISTS update_artifact_dismissal"),
                    script + " 应使用 IF NOT EXISTS");
        }
    }

    @Test
    void oracleAndSqlServerUseNativeTypesWithoutIfNotExists() throws Exception {
        String oracle = read("db/schema-oracle.sql");
        assertTrue(oracle.contains("CREATE TABLE update_artifact_dismissal"));
        assertTrue(oracle.contains("NUMBER(19)"));
        assertTrue(oracle.contains("VARCHAR2(160)"));
        assertFalse(oracle.contains("CREATE TABLE IF NOT EXISTS update_artifact_dismissal"));

        String sqlServer = read("db/schema-sqlserver.sql");
        assertTrue(sqlServer.contains("CREATE TABLE update_artifact_dismissal"));
        assertTrue(sqlServer.contains("NVARCHAR(160)"));
        assertTrue(sqlServer.contains("DATETIME2"));
        assertFalse(sqlServer.contains("CREATE TABLE IF NOT EXISTS update_artifact_dismissal"));
    }

    @Test
    void columnWidthsAreConsistentAcrossDialects() throws Exception {
        for (String script : new String[]{
                "db/schema-h2.sql", "db/schema-mysql.sql", "db/schema-postgresql.sql",
                "db/schema-oracle.sql", "db/schema-sqlserver.sql"}) {
            String sql = read(script);
            assertTrue(sql.contains("160"), script + " 的 artifact_key 宽度应统一");
            int definitions = countOccurrences(sql, "CREATE TABLE update_artifact_dismissal")
                    + countOccurrences(sql, "CREATE TABLE IF NOT EXISTS update_artifact_dismissal");
            assertEquals(1, definitions, script + " 只应出现一次新表定义");
        }
    }

    @Test
    void everyDialectScriptContainsSsoConfigTable() throws Exception {
        for (String script : new String[]{
                "db/schema-h2.sql", "db/schema-mysql.sql", "db/schema-postgresql.sql",
                "db/schema-oracle.sql", "db/schema-sqlserver.sql"}) {
            String sql = read(script).toLowerCase();
            assertTrue(sql.contains("sso_config"), script + " 缺少 sso_config");
            assertTrue(sql.contains("auth_mode"), script + " 缺少 auth_mode 列");
            assertFalse(sql.contains(" mode "), script + " auth_mode 不应写入保留字 mode");
        }
    }

    @Test
    void ssoConfigDdlUsesDialectTypes() throws Exception {
        for (String script : new String[]{
                "db/schema-h2.sql", "db/schema-mysql.sql", "db/schema-postgresql.sql"}) {
            assertTrue(read(script).contains("CREATE TABLE IF NOT EXISTS sso_config"),
                    script + " 应使用 IF NOT EXISTS");
        }

        String oracle = read("db/schema-oracle.sql");
        assertTrue(oracle.contains("CREATE TABLE sso_config"));
        assertTrue(oracle.contains("VARCHAR2(16)"));
        assertFalse(oracle.contains("CREATE TABLE IF NOT EXISTS sso_config"));

        String sqlServer = read("db/schema-sqlserver.sql");
        assertTrue(sqlServer.contains("CREATE TABLE sso_config"));
        assertTrue(sqlServer.contains("NVARCHAR(16)"));
        assertTrue(sqlServer.contains("DATETIME2"));
        assertFalse(sqlServer.contains("CREATE TABLE IF NOT EXISTS sso_config"));
    }

    private static String read(String path) throws Exception {
        try (InputStream in = SchemaDdlResourceTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺失资源: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
