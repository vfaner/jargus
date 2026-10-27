package com.qqmu.jargus.controller;

import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.security.ClientIpResolver;
import com.qqmu.jargus.security.SsoLoginRateLimiter;
import com.qqmu.jargus.service.SsoAuthService;
import com.qqmu.jargus.service.SsoConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoEntryControllerTest {

    private SsoConfigService configService;
    private SsoAuthService authService;
    private SsoLoginRateLimiter rateLimiter;
    private ClientIpResolver ipResolver;
    private SsoEntryController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        configService = mock(SsoConfigService.class);
        authService = mock(SsoAuthService.class);
        rateLimiter = mock(SsoLoginRateLimiter.class);
        ipResolver = mock(ClientIpResolver.class);
        controller = new SsoEntryController(configService, authService, rateLimiter, ipResolver);
        ReflectionTestUtils.setField(controller, "jwtExpireHours", 24);

        request = new MockHttpServletRequest("GET", "/sso/entry");
        response = new MockHttpServletResponse();
        when(ipResolver.resolve(request)).thenReturn("1.2.3.4");
        when(rateLimiter.isBlocked("1.2.3.4")).thenReturn(false);
    }

    @Test
    void disabledSsoRedirectsToLogin() throws Exception {
        when(configService.getRaw()).thenReturn(SsoConfigService.defaults());

        controller.entry(Map.of("ticket", "t"), request, response);

        assertEquals("/login?ssoDisabled", response.getRedirectedUrl());
        verify(authService, never()).loginByTicket(any(), anyString());
    }

    @Test
    void missingTicketRedirectsAndCountsFailure() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());

        controller.entry(new HashMap<>(), request, response);

        assertEquals("/login?ssoTicketMissing", response.getRedirectedUrl());
        verify(rateLimiter).recordFailure("1.2.3.4");
    }

    @Test
    void oversizedTicketIsRejected() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());

        controller.entry(Map.of("ticket", "x".repeat(4097)), request, response);

        assertEquals("/login?ssoTicketError", response.getRedirectedUrl());
        verify(rateLimiter).recordFailure("1.2.3.4");
    }

    @Test
    void blockedSourceGetsRetryLater() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());
        when(rateLimiter.isBlocked("1.2.3.4")).thenReturn(true);

        controller.entry(Map.of("ticket", "t"), request, response);

        assertEquals("/login?ssoTooManyAttempts", response.getRedirectedUrl());
        verify(authService, never()).loginByTicket(any(), anyString());
    }

    @Test
    void successIssuesCookieAndIgnoresNext() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole("VIEWER");
        when(authService.loginByTicket(any(), anyString()))
                .thenReturn(new SsoAuthService.SsoLoginResult(user, "jwt-token"));

        controller.entry(Map.of("ticket", "t", "next", "https://evil.com"), request, response);

        assertEquals("/dashboard", response.getRedirectedUrl());
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie != null && cookie.contains("jargus_token=jwt-token"));
        verify(rateLimiter).reset("1.2.3.4");
    }

    @Test
    void failedTicketRedirectsAndCountsFailure() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());
        when(authService.loginByTicket(any(), anyString()))
                .thenThrow(new SsoLoginException("票据校验未通过"));

        controller.entry(Map.of("ticket", "t"), request, response);

        assertEquals("/login?ssoTicketError", response.getRedirectedUrl());
        verify(rateLimiter).recordFailure("1.2.3.4");
    }

    @Test
    void unexpectedExceptionStillReturns302() throws Exception {
        when(configService.getRaw()).thenReturn(usableTicketConfig());
        when(authService.loginByTicket(any(), anyString()))
                .thenThrow(new IllegalStateException("db down"));

        controller.entry(Map.of("ticket", "t"), request, response);

        assertEquals("/login?ssoTicketError", response.getRedirectedUrl());
    }

    private static SsoConfig usableTicketConfig() {
        SsoConfig config = SsoConfigService.defaults();
        config.setIsEnabled(true);
        config.setAuthMode(SsoConfig.MODE_TICKET);
        config.setLoginEntryUrl("https://sso.example.com/login");
        config.setValidateUrl("https://sso.example.com/validate");
        config.setUserField("username");
        return config;
    }
}
