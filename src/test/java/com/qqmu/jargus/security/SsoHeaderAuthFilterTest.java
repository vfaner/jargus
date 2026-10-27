package com.qqmu.jargus.security;

import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.service.SsoAuthService;
import com.qqmu.jargus.service.SsoConfigService;
import com.qqmu.jargus.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoHeaderAuthFilterTest {

    private SsoConfigService configService;
    private SsoAuthService authService;
    private TrustedProxyMatcher trustedProxyMatcher;
    private JwtUtil jwtUtil;
    private SsoHeaderAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final AtomicBoolean chained = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        configService = mock(SsoConfigService.class);
        authService = mock(SsoAuthService.class);
        trustedProxyMatcher = mock(TrustedProxyMatcher.class);
        jwtUtil = mock(JwtUtil.class);
        filter = new SsoHeaderAuthFilter(configService, authService, trustedProxyMatcher, jwtUtil);
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        ReflectionTestUtils.setField(filter, "jwtExpireHours", 24);

        request = new MockHttpServletRequest("GET", "/dashboard");
        request.setRequestURI("/dashboard");
        request.setRemoteAddr("127.0.0.1");
        response = new MockHttpServletResponse();
        chained.set(false);
    }

    @Test
    void skipsWhenAuthDisabled() throws Exception {
        ReflectionTestUtils.setField(filter, "authEnabled", false);
        run();
        assertTrue(chained.get());
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void skipsPublicEntry() throws Exception {
        request.setRequestURI("/sso/entry");
        run();
        assertTrue(chained.get());
        verify(configService, never()).getRaw();
    }

    @Test
    void skipsWhenSsoDisabledOrTicketMode() throws Exception {
        when(configService.getRaw()).thenReturn(SsoConfigService.defaults());
        run();
        assertTrue(chained.get());

        SsoConfig ticket = headerConfig();
        ticket.setAuthMode(SsoConfig.MODE_TICKET);
        when(configService.getRaw()).thenReturn(ticket);
        run();
        assertTrue(chained.get());
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void skipsWhenExistingJwtIsValid() throws Exception {
        request.addHeader("Authorization", "Bearer good");
        when(jwtUtil.validateToken("good")).thenReturn(true);

        run();

        assertTrue(chained.get());
        // 已登录请求不应再读 sso_config
        verify(configService, never()).getRaw();
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void untrustedPeerIsNotAuthenticated() throws Exception {
        when(configService.getRaw()).thenReturn(headerConfig());
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(false);
        request.addHeader("X-Auth-User", "alice");

        run();

        assertTrue(chained.get());
        assertNull(request.getAttribute(SsoHeaderAuthFilter.AUTHENTICATED_USER_ATTRIBUTE));
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void secretMismatchFailsClosed() throws Exception {
        SsoConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue("expected");
        when(configService.getRaw()).thenReturn(config);
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Secret", "wrong");

        run();

        assertTrue(chained.get());
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void missingSecretFailsClosed() throws Exception {
        SsoConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue("expected");
        when(configService.getRaw()).thenReturn(config);
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);
        request.addHeader("X-Auth-User", "alice");

        run();

        assertTrue(chained.get());
        verify(authService, never()).authenticateHeader(any(), anyString(), anyBoolean());
    }

    @Test
    void matchingSecretAndHeadersAuthenticate() throws Exception {
        SsoConfig config = headerConfig();
        config.setSecretHeaderName("X-Auth-Secret");
        config.setSecretHeaderValue("expected");
        when(configService.getRaw()).thenReturn(config);
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);
        request.addHeader("X-Auth-User", "alice");
        request.addHeader("X-Auth-Admin", "true");
        request.addHeader("X-Auth-Secret", "expected");
        when(authService.authenticateHeader(any(), anyString(), anyBoolean()))
                .thenReturn(new SsoAuthService.SsoLoginResult(enabledUser(), "jwt"));

        run();

        assertTrue(chained.get());
        assertNotNull(request.getAttribute(SsoHeaderAuthFilter.AUTHENTICATED_USER_ATTRIBUTE));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie != null && cookie.contains("jargus_token=jwt"));
        verify(authService).authenticateHeader(config, "alice", true);
    }

    @Test
    void noUsernameHeaderPassesThroughUnauthenticated() throws Exception {
        when(configService.getRaw()).thenReturn(headerConfig());
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);

        run();

        assertTrue(chained.get());
        assertNull(request.getAttribute(SsoHeaderAuthFilter.AUTHENTICATED_USER_ATTRIBUTE));
    }

    @Test
    void failureOnPageRedirectsToLogin() throws Exception {
        request.setRequestURI("/dashboard");
        when(configService.getRaw()).thenReturn(headerConfig());
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);
        request.addHeader("X-Auth-User", "alice");
        when(authService.authenticateHeader(any(), anyString(), anyBoolean()))
                .thenThrow(new SsoLoginException("用户已被禁用"));

        run();

        assertFalse(chained.get());
        assertEquals("/login?ssoHeaderError", response.getRedirectedUrl());
    }

    @Test
    void failureOnApiReturns403Json() throws Exception {
        request.setRequestURI("/api/tasks");
        request.setMethod("GET");
        when(configService.getRaw()).thenReturn(headerConfig());
        when(trustedProxyMatcher.isTrusted("127.0.0.1")).thenReturn(true);
        request.addHeader("X-Auth-User", "alice");
        when(authService.authenticateHeader(any(), anyString(), anyBoolean()))
                .thenThrow(new SsoLoginException("用户已被禁用"));

        run();

        assertFalse(chained.get());
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("403"));
    }

    private void run() throws Exception {
        filter.doFilter(request, response, (req, res) -> chained.set(true));
    }

    private static SsoConfig headerConfig() {
        SsoConfig config = SsoConfigService.defaults();
        config.setIsEnabled(true);
        config.setAuthMode(SsoConfig.MODE_HEADER);
        config.setUserHeader("X-Auth-User");
        config.setAdminHeader("X-Auth-Admin");
        config.setAdminValue("true");
        config.setSecretHeaderName("");
        config.setSecretHeaderValue(null);
        return config;
    }

    private static SysUser enabledUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole("VIEWER");
        user.setSource("SSO");
        user.setIsEnabled(true);
        return user;
    }
}
