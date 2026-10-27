package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qqmu.jargus.entity.RemoteAuthConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private SysUserMapper userMapper;
    private JwtUtil jwtUtil;
    private RemoteAuthConfigService remoteAuthConfigService;
    private SsoConfigService ssoConfigService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        jwtUtil = mock(JwtUtil.class);
        remoteAuthConfigService = mock(RemoteAuthConfigService.class);
        ssoConfigService = mock(SsoConfigService.class);
        authService = new AuthService(userMapper, jwtUtil, remoteAuthConfigService, ssoConfigService);
        when(jwtUtil.generateToken(any(), anyString(), anyString())).thenReturn("tok");
    }

    @Test
    void localLoginRejectedWithoutDatabaseLookupWhenDisabled() {
        when(ssoConfigService.isLocalLoginEnabled()).thenReturn(false);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> authService.loginLocal("admin", "123456"));
        assertEquals("本地登录已关闭，请使用企业统一认证", e.getMessage());
        verify(userMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    void localLoginStillWorksWhenSsoEnabledButLocalAllowed() {
        when(ssoConfigService.isLocalLoginEnabled()).thenReturn(true);
        SysUser user = localUser();
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);

        AuthService.LoginResult result = authService.loginLocal("admin", "pw");

        assertEquals("tok", result.getToken());
        assertEquals("admin", result.getUser().getUsername());
        verify(userMapper).updateById(user);
    }

    @Test
    void remoteLoginIsNotAffectedByLocalLoginSwitch() {
        when(ssoConfigService.isLocalLoginEnabled()).thenReturn(false);

        RemoteAuthConfig config = new RemoteAuthConfig();
        config.setId(3L);
        config.setConfigName("oa");
        config.setIsEnabled(true);
        config.setLoginUrl("https://oa.example.com/login");
        when(remoteAuthConfigService.getRawById(3L)).thenReturn(config);
        when(remoteAuthConfigService.invokeLogin(any(), anyString(), anyString()))
                .thenReturn(Map.of("username", "bob", "nickname", "Bob"));

        SysUser remote = new SysUser();
        remote.setId(5L);
        remote.setUsername("bob");
        remote.setSource("REMOTE");
        remote.setRole("VIEWER");
        remote.setIsEnabled(true);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(remote);

        AuthService.LoginResult result = authService.loginRemote("bob", "pw", 3L);

        assertEquals("tok", result.getToken());
        assertEquals("REMOTE", result.getUser().getSource());
    }

    private static SysUser localUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setNickname("Admin");
        user.setSource("LOCAL");
        user.setRole("ADMIN");
        user.setIsEnabled(true);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("pw"));
        return user;
    }
}
