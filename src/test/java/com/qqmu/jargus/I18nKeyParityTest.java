package com.qqmu.jargus;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两份 i18n 键集必须完全一致，否则切换语言会出现原始 key。 */
class I18nKeyParityTest {

    @Test
    void englishAndChineseHaveSameKeys() throws Exception {
        Properties en = load("i18n/messages.properties");
        Properties zh = load("i18n/messages_zh_CN.properties");

        Set<String> enKeys = new TreeSet<>(en.stringPropertyNames());
        Set<String> zhKeys = new TreeSet<>(zh.stringPropertyNames());
        assertEquals(enKeys, zhKeys);
    }

    @Test
    void artifactNoticeKeysExist() throws Exception {
        Properties en = load("i18n/messages.properties");
        Properties zh = load("i18n/messages_zh_CN.properties");

        for (String key : new String[]{
                "settings.artifactUpdated",
                "settings.artifactUpdatedShort",
                "settings.artifactDismiss",
                "settings.artifactDismissHelp",
                "settings.artifactDismissFailed"}) {
            assertTrue(en.containsKey(key), "英文缺少 " + key);
            assertTrue(zh.containsKey(key), "中文缺少 " + key);
        }
        assertEquals("Latest version", en.getProperty("settings.latestVersion"));
        assertEquals("最新版本", zh.getProperty("settings.latestVersion"));
    }

    @Test
    void ssoAndLoginKeysExist() throws Exception {
        Properties en = load("i18n/messages.properties");
        Properties zh = load("i18n/messages_zh_CN.properties");

        for (String key : new String[]{
                "login.sso.button",
                "login.sso.headerNotice",
                "login.sso.error.ticketMissing",
                "login.sso.error.ticketError",
                "login.sso.error.tooManyAttempts",
                "login.sso.error.disabled",
                "login.sso.error.header",
                "remoteauth.sso.title",
                "remoteauth.sso.enabled",
                "remoteauth.sso.localLogin",
                "remoteauth.sso.adminUsers",
                "remoteauth.sso.testValidate",
                "remoteauth.sso.testHeaders"}) {
            assertTrue(en.containsKey(key), "英文缺少 " + key);
            assertTrue(zh.containsKey(key), "中文缺少 " + key);
        }
        assertEquals("Unified auth", en.getProperty("nav.remoteAuth"));
        assertEquals("统一认证", zh.getProperty("nav.remoteAuth"));
    }

    private static Properties load(String path) throws Exception {
        Properties properties = new Properties();
        try (InputStream in = I18nKeyParityTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("缺失资源: " + path);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
