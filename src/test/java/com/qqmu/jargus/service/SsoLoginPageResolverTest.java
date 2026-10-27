package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.SsoConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoLoginPageResolverTest {

    @Test
    void disabledSsoKeepsOriginalLoginPage() {
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(offConfig(), true, false, Map.of());

        assertFalse(state.ssoEnabled());
        assertFalse(state.showSsoButton());
        assertTrue(state.showLocalForm());
        assertFalse(state.showRemoteTab());
        assertFalse(state.redirectToSso());
        assertNull(state.ssoErrorKey());
    }

    @Test
    void ticketModeShowsButtonAndLocalForm() {
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(ticketConfig(), true, false, Map.of());

        assertTrue(state.ssoEnabled());
        assertTrue(state.showSsoButton());
        assertEquals("https://sso.example.com/login", state.ssoEntryUrl());
        assertTrue(state.showLocalForm());
        assertFalse(state.redirectToSso());
    }

    @Test
    void redirectsOnlyWhenLocalLoginOffAndNoOtherEntry() {
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(ticketConfig(), false, false, Map.of());
        assertTrue(state.redirectToSso());
        assertFalse(state.showLocalForm());
    }

    @Test
    void remoteConfigsPreventAutoRedirect() {
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(ticketConfig(), false, true, Map.of());
        assertFalse(state.redirectToSso());
        assertTrue(state.showRemoteTab());
        assertTrue(state.showSsoButton());
        assertFalse(state.showLocalForm());
    }

    @Test
    void errorParamNeverRedirects() {
        SsoLoginPageResolver.LoginPageState state = SsoLoginPageResolver.resolve(
                ticketConfig(), false, false, Map.of("ssoTicketError", ""));

        assertFalse(state.redirectToSso());
        assertEquals("login.sso.error.ticketError", state.ssoErrorKey());
        assertTrue(state.showSsoButton());
    }

    @Test
    void errorKeysCoverEveryEntryControllerCode() {
        assertEquals("login.sso.error.ticketMissing", errorKey("ssoTicketMissing"));
        assertEquals("login.sso.error.tooManyAttempts", errorKey("ssoTooManyAttempts"));
        assertEquals("login.sso.error.disabled", errorKey("ssoDisabled"));
        assertEquals("login.sso.error.header", errorKey("ssoHeaderError"));
    }

    @Test
    void headerModeShowsGatewayNotice() {
        SsoConfig config = headerConfig();
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(config, true, false, Map.of());

        assertTrue(state.showHeaderNotice());
        assertFalse(state.showSsoButton());
        assertTrue(state.showLocalForm());
        assertFalse(state.redirectToSso());
    }

    @Test
    void headerModeWithNoLocalOrRemoteLeavesNoticeOnly() {
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(headerConfig(), false, false, Map.of());

        assertTrue(state.showHeaderNotice());
        assertFalse(state.showLocalForm());
        assertFalse(state.showRemoteTab());
        assertFalse(state.redirectToSso());
    }

    @Test
    void unusableTicketConfigIsTreatedAsDisabled() {
        SsoConfig config = ticketConfig();
        config.setValidateUrl("");
        SsoLoginPageResolver.LoginPageState state =
                SsoLoginPageResolver.resolve(config, true, false, Map.of());
        assertFalse(state.ssoEnabled());
        assertFalse(state.showSsoButton());
    }

    private static String errorKey(String param) {
        return SsoLoginPageResolver.resolve(ticketConfig(), true, false, Map.of(param, ""))
                .ssoErrorKey();
    }

    static SsoConfig offConfig() {
        return SsoConfigService.defaults();
    }

    static SsoConfig ticketConfig() {
        SsoConfig config = SsoConfigService.defaults();
        config.setIsEnabled(true);
        config.setAuthMode(SsoConfig.MODE_TICKET);
        config.setLoginEntryUrl("https://sso.example.com/login");
        config.setValidateUrl("https://sso.example.com/validate");
        config.setUserField("username");
        return config;
    }

    static SsoConfig headerConfig() {
        SsoConfig config = SsoConfigService.defaults();
        config.setIsEnabled(true);
        config.setAuthMode(SsoConfig.MODE_HEADER);
        config.setUserHeader("X-Auth-User");
        config.setSecretHeaderName("");
        return config;
    }
}
