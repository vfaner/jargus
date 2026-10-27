package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoAuthServiceTest {

    private SsoTicketClient ticketClient;
    private SysUserMapper userMapper;
    private JwtUtil jwtUtil;
    private SsoAuthService service;
    private SsoConfig config;

    @BeforeEach
    void setUp() {
        ticketClient = mock(SsoTicketClient.class);
        userMapper = mock(SysUserMapper.class);
        jwtUtil = mock(JwtUtil.class);
        service = new SsoAuthService(ticketClient, userMapper, jwtUtil);

        config = SsoConfigService.defaults();
        config.setIsEnabled(true);
        config.setAdminUsers("alice");
        when(jwtUtil.generateToken(any(), any(), any())).thenReturn("tok");
    }

    @Test
    void ticketIdentityDefaultsToViewer() {
        when(ticketClient.validate(eq(config), eq("t1")))
                .thenReturn(Map.of("success", true, "username", "bob", "isAdmin", false));

        SsoAuthService.TestIdentity identity = service.inspectTicket(config, "t1");
        assertEquals("bob", identity.username());
        assertEquals("VIEWER", identity.role());
        assertFalse(identity.adminMarker());
    }

    @Test
    void adminFlagAndWhitelistBothGrantAdmin() {
        when(ticketClient.validate(any(), any()))
                .thenReturn(Map.of("success", true, "username", "bob", "isAdmin", true));
        assertEquals("ADMIN", service.inspectTicket(config, "t").role());

        when(ticketClient.validate(any(), any()))
                .thenReturn(Map.of("success", true, "username", "ALICE", "isAdmin", false));
        assertEquals("ADMIN", service.inspectTicket(config, "t").role());
    }

    @Test
    void rejectsUnsuccessfulTicket() {
        when(ticketClient.validate(any(), any()))
                .thenReturn(Map.of("success", false, "username", "bob"));
        assertThrows(SsoLoginException.class, () -> service.inspectTicket(config, "t"));
    }

    @Test
    void rejectsMissingOrMalformedUsername() {
        when(ticketClient.validate(any(), any())).thenReturn(Map.of("success", true));
        assertThrows(SsoLoginException.class, () -> service.inspectTicket(config, "t"));

        when(ticketClient.validate(any(), any()))
                .thenReturn(Map.of("success", true, "username", "bad user"));
        assertThrows(SsoLoginException.class, () -> service.inspectTicket(config, "t"));
    }

    @Test
    void sanitizeUsernameBounds() {
        assertEquals("alice", SsoAuthService.sanitizeUsername(" alice "));
        assertThrows(SsoLoginException.class, () -> SsoAuthService.sanitizeUsername(null));
        assertThrows(SsoLoginException.class, () -> SsoAuthService.sanitizeUsername("   "));
        assertThrows(SsoLoginException.class, () -> SsoAuthService.sanitizeUsername("x".repeat(65)));
        assertThrows(SsoLoginException.class,
                () -> SsoAuthService.sanitizeUsername("a\tb"));
    }

    @Test
    void createsSsoUserWithoutPassword() {
        when(ticketClient.validate(any(), any()))
                .thenReturn(Map.of("success", true, "username", "bob", "isAdmin", false));
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        SsoAuthService.SsoLoginResult result = service.loginByTicket(config, "t");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        SysUser created = captor.getValue();
        assertEquals("bob", created.getUsername());
        assertEquals("VIEWER", created.getRole());
        assertEquals("SSO", created.getSource());
        assertEquals("", created.getPasswordHash());
        assertTrue(created.getIsEnabled());
        assertFalse(created.getIsPasswordDefault());
        assertEquals("tok", result.token());
    }

    @Test
    void neverTakesOverLocalAccount() {
        SysUser local = existingUser("alice", "LOCAL", "ADMIN", true);
        local.setPasswordHash("$2a$hash");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(local);

        SsoAuthService.TestIdentity identity = new SsoAuthService.TestIdentity("alice", "ADMIN", false);
        assertThrows(SsoLoginException.class,
                () -> service.authenticateHeader(config, identity.username(), identity.adminMarker()));

        verify(userMapper, never()).updateById(any());
        verify(userMapper, never()).insert(any());
        assertEquals("$2a$hash", local.getPasswordHash());
        assertEquals("ADMIN", local.getRole());
    }

    @Test
    void neverReEnablesDisabledAccount() {
        SysUser disabled = existingUser("bob", "SSO", "VIEWER", false);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(disabled);

        assertThrows(SsoLoginException.class,
                () -> service.authenticateHeader(config, "bob", false));
        verify(userMapper, never()).updateById(any());
    }

    @Test
    void remoteUserKeepsSourceButRefreshesRole() {
        SysUser remote = existingUser("bob", "REMOTE", "VIEWER", true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(remote);

        service.authenticateHeader(config, "bob", true);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("REMOTE", captor.getValue().getSource());
        assertEquals("ADMIN", captor.getValue().getRole());
        assertTrue(captor.getValue().getLastLoginAt() != null);
    }

    @Test
    void existingSsoUserRefreshesRoleAndSource() {
        SysUser sso = existingUser("bob", "SSO", "VIEWER", true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(sso);

        service.authenticateHeader(config, "bob", false);

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("SSO", captor.getValue().getSource());
        assertEquals("VIEWER", captor.getValue().getRole());
    }

    @Test
    void reusesCaseVariantExistingUserInsteadOfCreatingDuplicate() {
        SysUser existing = existingUser("Bob", "SSO", "VIEWER", true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));

        service.authenticateHeader(config, "bob", false);

        verify(userMapper, never()).insert(any());
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(captor.capture());
        assertEquals("Bob", captor.getValue().getUsername());
    }

    @Test
    void caseVariantLocalAccountIsStillProtected() {
        SysUser local = existingUser("Admin", "LOCAL", "ADMIN", true);
        local.setPasswordHash("$2a$hash");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(local));

        assertThrows(SsoLoginException.class,
                () -> service.authenticateHeader(config, "admin", false));

        verify(userMapper, never()).insert(any());
        verify(userMapper, never()).updateById(any());
        assertEquals("ADMIN", local.getRole());
    }

    private static SysUser existingUser(String username, String source, String role, boolean enabled) {
        SysUser user = new SysUser();
        user.setId(9L);
        user.setUsername(username);
        user.setSource(source);
        user.setRole(role);
        user.setIsEnabled(enabled);
        user.setPasswordHash("");
        user.setNickname(username);
        return user;
    }
}
