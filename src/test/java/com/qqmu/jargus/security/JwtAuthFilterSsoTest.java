package com.qqmu.jargus.security;

import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthFilterSsoTest {

    private JwtUtil jwtUtil;
    private SysUserMapper userMapper;
    private JwtAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final AtomicReference<UserContext.CurrentUser> seen = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        userMapper = mock(SysUserMapper.class);
        filter = new JwtAuthFilter(jwtUtil, userMapper);
        ReflectionTestUtils.setField(filter, "authEnabled", true);
        request = new MockHttpServletRequest("GET", "/dashboard");
        request.setRequestURI("/dashboard");
        response = new MockHttpServletResponse();
        seen.set(null);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void headerFilterUserIsReusedWithoutTokenLookup() throws Exception {
        SysUser user = user(7L, "alice", "ADMIN", true);
        request.setAttribute(SsoHeaderAuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);

        run();

        assertNotNull(seen.get());
        assertEquals("alice", seen.get().getUsername());
        assertEquals("ADMIN", seen.get().getRole());
        verify(userMapper, never()).selectById(7L);
        assertNull(UserContext.get(), "请求结束后必须清理上下文");
    }

    @Test
    void disabledHeaderUserIsIgnoredAndRequestRejected() throws Exception {
        request.setRequestURI("/api/tasks");
        SysUser user = user(7L, "alice", "ADMIN", false);
        request.setAttribute(SsoHeaderAuthFilter.AUTHENTICATED_USER_ATTRIBUTE, user);

        run();

        assertEquals(401, response.getStatus());
        assertNull(seen.get());
    }

    @Test
    void ssoEntryIsPublic() throws Exception {
        request.setRequestURI("/sso/entry");
        request.setMethod("GET");

        run();

        assertNull(seen.get());
        verify(userMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cookieTokenStillBuildsContext() throws Exception {
        request.setCookies(new Cookie(JwtAuthFilter.TOKEN_COOKIE, "token-1"));
        when(jwtUtil.validateToken("token-1")).thenReturn(true);
        when(jwtUtil.getUserId("token-1")).thenReturn(7L);
        when(userMapper.selectById(7L)).thenReturn(user(7L, "bob", "VIEWER", true));

        run();

        assertNotNull(seen.get());
        assertEquals("bob", seen.get().getUsername());
    }

    @Test
    void bearerTokenStillBuildsContext() throws Exception {
        request.addHeader("Authorization", "Bearer token-2");
        when(jwtUtil.validateToken("token-2")).thenReturn(true);
        when(jwtUtil.getUserId("token-2")).thenReturn(8L);
        when(userMapper.selectById(8L)).thenReturn(user(8L, "carol", "VIEWER", true));

        run();

        assertNotNull(seen.get());
        assertEquals("carol", seen.get().getUsername());
    }

    private void run() throws Exception {
        filter.doFilter(request, response, (req, res) -> seen.set(UserContext.get()));
    }

    private static SysUser user(Long id, String username, String role, boolean enabled) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setRole(role);
        user.setSource("SSO");
        user.setIsEnabled(enabled);
        return user;
    }
}
