package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.RemoteAuthConfig;
import com.qqmu.jargus.security.JwtAuthFilter;
import com.qqmu.jargus.security.PublicAccess;
import com.qqmu.jargus.security.UserContext;
import com.qqmu.jargus.service.AuthService;
import com.qqmu.jargus.service.RemoteAuthConfigService;
import com.qqmu.jargus.service.SsoConfigService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 认证控制器
 *
 * 登录成功后同时返回 token（body，供 localStorage 里的 Bearer 调用）并下发
 * httpOnly Cookie（供 Thymeleaf 页面导航鉴权）。
 */
@PublicAccess
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RemoteAuthConfigService remoteAuthConfigService;
    private final SsoConfigService ssoConfigService;

    @Value("${app.jwt-expire-hours:24}")
    private int jwtExpireHours;

    /**
     * 本地登录
     */
    @PostMapping("/login")
    public Result<AuthService.LoginResult> login(@RequestBody Map<String, String> body,
                                                 HttpServletResponse response) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return Result.error("用户名和密码不能为空");
        }
        AuthService.LoginResult result = authService.loginLocal(username, password);
        issueCookie(response, result.getToken());
        return Result.success(result);
    }

    /**
     * 远端登录（OA 对接）
     */
    @PostMapping("/remote-login")
    public Result<AuthService.LoginResult> remoteLogin(@RequestBody Map<String, String> body,
                                                       HttpServletResponse response) {
        String username = body.get("username");
        String password = body.get("password");
        String configIdStr = body.get("configId");
        Long configId;
        try {
            configId = configIdStr != null && !configIdStr.isBlank() ? Long.parseLong(configIdStr.trim()) : null;
        } catch (NumberFormatException e) {
            // 非法 configId 是客户端参数错误，不能落到全局异常处理器变成 500
            return Result.error(400, "远端登录方式参数不正确");
        }
        AuthService.LoginResult result = authService.loginRemote(username, password, configId);
        issueCookie(response, result.getToken());
        return Result.success(result);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/userinfo")
    public Result<AuthService.SysUserInfo> getUserInfo() {
        UserContext.CurrentUser user = UserContext.get();
        if (user == null) {
            return Result.error("未登录");
        }
        AuthService.SysUserInfo info = authService.getUserInfo(user.getId());
        return Result.success(info);
    }

    /**
     * 修改密码（仅本地用户）
     */
    @PostMapping("/change-password")
    public Result<Boolean> changePassword(@RequestBody Map<String, String> body) {
        UserContext.CurrentUser user = UserContext.get();
        if (user == null) {
            return Result.error("未登录");
        }
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");
        if (oldPassword == null || newPassword == null) {
            return Result.error("原密码和新密码不能为空");
        }
        boolean result = authService.changePassword(user.getId(), oldPassword, newPassword);
        return Result.success(result);
    }

    /**
     * 获取可用的远端登录配置列表（供登录页选择）
     */
    @GetMapping("/remote-configs")
    public Result<List<RemoteAuthConfig>> getRemoteConfigs() {
        List<RemoteAuthConfig> configs = remoteAuthConfigService.listEnabled();
        // 清除敏感信息
        configs.forEach(c -> c.setClientSecret(null));
        return Result.success(configs);
    }

    /**
     * 登出：清除 httpOnly Cookie（token 本身无状态）
     */
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, JwtAuthFilter.clearAuthCookie().toString());
        Map<String, Object> data = new HashMap<>();
        String redirectUrl = ssoConfigService.safeLogoutRedirect();
        if (redirectUrl != null) {
            data.put("redirectUrl", redirectUrl);
        }
        return Result.success(data);
    }

    private void issueCookie(HttpServletResponse response, String token) {
        long seconds = jwtExpireHours * 3600L;
        response.addHeader(HttpHeaders.SET_COOKIE, JwtAuthFilter.authCookie(token, seconds).toString());
    }
}
