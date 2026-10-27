package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.SsoConfigForm;
import com.qqmu.jargus.dto.SsoConfigView;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.mapper.SsoConfigMapper;
import com.qqmu.jargus.util.CryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoConfigServiceTest {

    private SsoConfigMapper mapper;
    private SsoConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SsoConfigMapper.class);
        service = new SsoConfigService(mapper);
    }

    @Test
    void defaultsWhenNoRow() {
        when(mapper.selectById(1L)).thenReturn(null);
        SsoConfig config = service.getRaw();

        assertFalse(config.getIsEnabled());
        assertEquals(SsoConfig.MODE_TICKET, config.getAuthMode());
        assertTrue(config.getLocalLoginEnabled());
        assertEquals("X-Auth-User", config.getUserHeader());
        assertEquals("X-Auth-Secret", config.getSecretHeaderName());
        assertEquals(5000, config.getTimeoutMs());
        assertNull(config.getSecretHeaderValue());
    }

    @Test
    void firstSaveInsertsSingletonRowAndEncryptsSecret() {
        when(mapper.selectById(1L)).thenReturn(null);

        SsoConfigForm form = headerForm();
        form.setSecretHeaderValue("s3cret");
        service.save(form);

        ArgumentCaptor<SsoConfig> captor = ArgumentCaptor.forClass(SsoConfig.class);
        verify(mapper).insert(captor.capture());
        SsoConfig inserted = captor.getValue();
        assertEquals(1L, inserted.getId());
        assertEquals(CryptoUtil.encrypt("s3cret"), inserted.getSecretHeaderValue());
        assertFalse(inserted.getSecretHeaderValue().contains("s3cret"));
    }

    @Test
    void blankSecretKeepsExistingCipher() {
        String cipher = CryptoUtil.encrypt("old-secret");
        SsoConfig row = headerRow();
        row.setSecretHeaderValue(cipher);
        when(mapper.selectById(1L)).thenReturn(row);

        SsoConfigForm form = headerForm();
        form.setSecretHeaderValue("");
        service.save(form);

        ArgumentCaptor<SsoConfig> captor = ArgumentCaptor.forClass(SsoConfig.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(cipher, captor.getValue().getSecretHeaderValue());
    }

    @Test
    void maskedSecretKeepsExistingCipher() {
        String cipher = CryptoUtil.encrypt("old-secret");
        SsoConfig row = headerRow();
        row.setSecretHeaderValue(cipher);
        when(mapper.selectById(1L)).thenReturn(row);

        SsoConfigForm form = headerForm();
        form.setSecretHeaderValue("****");
        service.save(form);

        ArgumentCaptor<SsoConfig> captor = ArgumentCaptor.forClass(SsoConfig.class);
        verify(mapper).updateById(captor.capture());
        assertEquals(cipher, captor.getValue().getSecretHeaderValue());
    }

    @Test
    void unreadableCipherFailsClosed() {
        SsoConfig row = headerRow();
        row.setSecretHeaderValue("not-a-valid-cipher!!");
        when(mapper.selectById(1L)).thenReturn(row);

        SsoConfig config = service.getRaw();
        assertNull(config.getSecretHeaderValue());
        SsoConfigView view = service.getView();
        assertFalse(view.isSecretConfigured());
    }

    @Test
    void viewNeverExposesSecretValue() {
        for (Field field : SsoConfigView.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse((name.contains("secret") && name.endsWith("value"))
                            || name.contains("cipher"),
                    "视图模型不应包含密钥字段: " + field.getName());
        }
    }

    @Test
    void ticketModeRequiresEndpointFieldsWhenEnabled() {
        SsoConfigForm form = basicForm();
        form.setIsEnabled(true);
        form.setLoginEntryUrl("https://sso.example.com/login");
        form.setValidateUrl("https://sso.example.com/validate");
        form.setUserField("username");
        assertNull(service.validate(form));

        form.setUserField("");
        assertNotNull(service.validate(form));
    }

    @Test
    void headerModeRequiresUserHeaderAndSecret() {
        SsoConfigForm form = headerForm();
        form.setUserHeader("");
        assertNotNull(service.validate(form), "启用的网关头模式必须配置用户名头");

        form = headerForm();
        form.setSecretHeaderName("X-Auth-Secret");
        form.setSecretHeaderValue("");
        assertNotNull(service.validate(form), "配置了密钥头就必须有密钥");
    }

    @Test
    void rejectsForbiddenHeaderNames() {
        SsoConfigForm form = headerForm();
        form.setUserHeader("Host");
        assertNotNull(service.validate(form));
        form.setUserHeader("X Auth User");
        assertNotNull(service.validate(form));
    }

    @Test
    void rejectsInvalidModeMethodAndTimeout() {
        SsoConfigForm form = basicForm();
        form.setAuthMode("SAML");
        assertNotNull(service.validate(form));

        form = basicForm();
        form.setValidateMethod("DELETE");
        assertNotNull(service.validate(form));

        form = basicForm();
        form.setTimeoutMs(999);
        assertNotNull(service.validate(form));
        form.setTimeoutMs(30001);
        assertNotNull(service.validate(form));
        form.setTimeoutMs(30000);
        assertNull(service.validate(form));
    }

    @Test
    void rejectsPlainHttpUnlessExplicitlyAllowed() {
        SsoConfigForm form = basicForm();
        form.setLoginEntryUrl("http://sso.example.com/login");
        assertNotNull(service.validate(form));
        form.setAllowInsecureHttp(true);
        assertNull(service.validate(form));
    }

    @Test
    void rejectsOpenRedirectLogoutTargets() {
        for (String target : new String[]{"//evil.com", "/\\evil.com", "javascript:alert(1)", "https://evil.com\\x"}) {
            SsoConfigForm form = basicForm();
            form.setLogoutRedirectUrl(target);
            assertNotNull(service.validate(form), "应拒绝: " + target);
        }
        SsoConfigForm ok = basicForm();
        ok.setLogoutRedirectUrl("/portal");
        assertNull(service.validate(ok));
        ok.setLogoutRedirectUrl("https://portal.example.com");
        assertNull(service.validate(ok));
    }

    @Test
    void adminWhitelistIsNormalizedAndBounded() {
        SsoConfigForm form = basicForm();
        form.setAdminUsers(" alice, , bob ,, ");
        assertNull(service.validate(form));
        verify(mapper, never()).insert(any());

        String tooMany = IntStream.range(0, 201)
                .mapToObj(i -> "u" + i)
                .collect(Collectors.joining(","));
        form.setAdminUsers(tooMany);
        assertNotNull(service.validate(form));

        form.setAdminUsers("x".repeat(65));
        assertNotNull(service.validate(form));
    }

    @Test
    void rejectsOversizedSecretByUtf8Bytes() {
        SsoConfigForm form = headerForm();
        form.setSecretHeaderValue("x".repeat(256));
        assertNull(service.validate(form), "256 字节应通过");

        form.setSecretHeaderValue("x".repeat(257));
        assertNotNull(service.validate(form), "超过 256 字节应拒绝，避免密文超出列宽被截断");

        form.setSecretHeaderValue("密".repeat(86));
        assertNotNull(service.validate(form), "中文按 UTF-8 字节计算");
    }

    @Test
    void rejectsOverlongUrlsAndFieldPaths() {
        SsoConfigForm form = basicForm();
        form.setLoginEntryUrl("https://example.com/" + "a".repeat(493));
        assertNotNull(service.validate(form));

        form = basicForm();
        form.setValidateUrl("https://example.com/" + "a".repeat(493));
        assertNotNull(service.validate(form));

        form = basicForm();
        form.setLogoutRedirectUrl("/" + "a".repeat(512));
        assertNotNull(service.validate(form));

        form = basicForm();
        form.setUserField("u".repeat(129));
        assertNotNull(service.validate(form));
        form.setUserField("u".repeat(128));
        assertNull(service.validate(form));
    }

    @Test
    void staticLocalLoginFallbackSharesInstanceRule() {
        SsoConfig config = SsoConfigService.defaults();
        config.setLocalLoginEnabled(false);
        assertTrue(SsoConfigService.isLocalLoginEnabled(config), "SSO 不可用时兜底开启本地登录");

        config.setIsEnabled(true);
        config.setLoginEntryUrl("https://sso.example.com/login");
        config.setValidateUrl("https://sso.example.com/validate");
        assertFalse(SsoConfigService.isLocalLoginEnabled(config));
    }

    @Test
    void inactiveTicketFieldsDoNotBlockHeaderModeSave() {
        SsoConfigForm form = headerForm();
        form.setSecretHeaderName("");
        form.setLoginEntryUrl("http://legacy.example.com/login");

        assertNull(service.validate(form), "网关头模式不应被隐藏的票据区块旧值阻断");
    }

    @Test
    void cannotDisableLocalLoginWithoutUsableSso() {
        SsoConfigForm form = basicForm();
        form.setIsEnabled(false);
        form.setLocalLoginEnabled(false);
        assertNotNull(service.validate(form));

        form = headerForm();
        form.setSecretHeaderName("");
        form.setLocalLoginEnabled(false);
        assertNull(service.validate(form));
    }

    @Test
    void localLoginFallsBackWhenSsoUnusable() {
        SsoConfig row = basicRow();
        row.setLocalLoginEnabled(false);
        row.setIsEnabled(false);
        when(mapper.selectById(1L)).thenReturn(row);
        assertTrue(service.isLocalLoginEnabled());

        row.setIsEnabled(true);
        row.setAuthMode(SsoConfig.MODE_TICKET);
        row.setLoginEntryUrl("https://sso.example.com/login");
        row.setValidateUrl("https://sso.example.com/validate");
        row.setUserField("username");
        assertFalse(service.isLocalLoginEnabled());
    }

    @Test
    void startupSafetyNetRestoresLocalLogin() {
        SsoConfig row = basicRow();
        row.setLocalLoginEnabled(false);
        row.setIsEnabled(false);
        when(mapper.selectById(1L)).thenReturn(row);

        service.applyStartupSafetyNets(false);

        ArgumentCaptor<SsoConfig> captor = ArgumentCaptor.forClass(SsoConfig.class);
        verify(mapper).updateById(captor.capture());
        assertTrue(captor.getValue().getLocalLoginEnabled());
    }

    @Test
    void breakGlassForcesLocalLoginEvenWhenSsoUsable() {
        SsoConfig row = headerRow();
        row.setSecretHeaderValue(CryptoUtil.encrypt("shared"));
        row.setLocalLoginEnabled(false);
        when(mapper.selectById(1L)).thenReturn(row);

        service.applyStartupSafetyNets(true);

        ArgumentCaptor<SsoConfig> captor = ArgumentCaptor.forClass(SsoConfig.class);
        verify(mapper).updateById(captor.capture());
        assertTrue(captor.getValue().getLocalLoginEnabled());
    }

    @Test
    void mapperFailureFallsBackToDefaults() {
        when(mapper.selectById(anyLong())).thenThrow(new RuntimeException("table missing"));
        SsoConfig config = service.getRaw();
        assertFalse(config.getIsEnabled());
        assertTrue(config.getLocalLoginEnabled());
    }

    @Test
    void safeLogoutRedirectValidatesStoredValue() {
        SsoConfig row = basicRow();
        row.setLogoutRedirectUrl("//evil.com");
        when(mapper.selectById(1L)).thenReturn(row);
        assertNull(service.safeLogoutRedirect());

        row.setLogoutRedirectUrl("https://portal.example.com/bye");
        assertNotNull(service.safeLogoutRedirect());
    }

    @Test
    void adminUserMatchingIsCaseInsensitive() {
        SsoConfig config = SsoConfigService.defaults();
        config.setAdminUsers(" Alice , bob ");
        assertTrue(SsoConfigService.isAdminUser(config, "alice"));
        assertTrue(SsoConfigService.isAdminUser(config, " BOB "));
        assertFalse(SsoConfigService.isAdminUser(config, "carol"));
    }

    @Test
    void constantTimeEqualsHandlesNulls() {
        assertFalse(SsoConfigService.constantTimeEquals(null, "x"));
        assertFalse(SsoConfigService.constantTimeEquals("x", null));
        assertTrue(SsoConfigService.constantTimeEquals("x", "x"));
        assertFalse(SsoConfigService.constantTimeEquals("x", "y"));
    }

    @Test
    void resolveForTestUsesSubmittedPlaintextAndNeverPersists() {
        when(mapper.selectById(1L)).thenReturn(null);
        SsoConfigForm form = headerForm();
        form.setSecretHeaderValue("plain");
        SsoConfig config = service.resolveForTest(form);
        assertEquals("plain", config.getSecretHeaderValue());
        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    // ── helpers ────────────────────────────────────────────────

    private static SsoConfigForm basicForm() {
        SsoConfigForm form = new SsoConfigForm();
        form.setIsEnabled(false);
        form.setAuthMode(SsoConfig.MODE_TICKET);
        form.setLocalLoginEnabled(true);
        form.setValidateMethod("GET");
        form.setTicketParam("ticket");
        form.setTimeoutMs(5000);
        return form;
    }

    private static SsoConfigForm headerForm() {
        SsoConfigForm form = basicForm();
        form.setIsEnabled(true);
        form.setAuthMode(SsoConfig.MODE_HEADER);
        form.setUserHeader("X-Auth-User");
        form.setAdminHeader("X-Auth-Admin");
        form.setAdminValue("true");
        form.setSecretHeaderName("X-Auth-Secret");
        return form;
    }

    private static SsoConfig basicRow() {
        SsoConfig row = new SsoConfig();
        row.setId(1L);
        row.setIsEnabled(false);
        row.setAuthMode(SsoConfig.MODE_TICKET);
        row.setLocalLoginEnabled(true);
        row.setValidateMethod("GET");
        row.setTicketParam("ticket");
        row.setTimeoutMs(5000);
        return row;
    }

    private static SsoConfig headerRow() {
        SsoConfig row = basicRow();
        row.setIsEnabled(true);
        row.setAuthMode(SsoConfig.MODE_HEADER);
        row.setUserHeader("X-Auth-User");
        row.setAdminHeader("X-Auth-Admin");
        row.setAdminValue("true");
        row.setSecretHeaderName("X-Auth-Secret");
        return row;
    }
}
