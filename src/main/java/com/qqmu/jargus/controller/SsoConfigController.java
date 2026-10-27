package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.dto.SsoConfigForm;
import com.qqmu.jargus.dto.SsoSettingsResponse;
import com.qqmu.jargus.dto.SsoTestRequest;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.security.RequiresRole;
import com.qqmu.jargus.security.TrustedProxyMatcher;
import com.qqmu.jargus.service.SsoAuthService;
import com.qqmu.jargus.service.SsoConfigService;
import com.qqmu.jargus.service.SsoTicketClient;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业统一认证配置接口（管理员）。
 */
@RestController
@RequestMapping("/api/sso")
@RequiredArgsConstructor
@RequiresRole("ADMIN")
public class SsoConfigController {

    private final SsoConfigService ssoConfigService;
    private final SsoAuthService ssoAuthService;
    private final SsoTicketClient ssoTicketClient;
    private final TrustedProxyMatcher trustedProxyMatcher;

    @GetMapping("/config")
    public Result<SsoSettingsResponse> getConfig() {
        return Result.success(new SsoSettingsResponse(
                ssoConfigService.getView(), trustedProxyMatcher.configuredRules()));
    }

    @PutMapping("/config")
    public Result<?> save(@RequestBody SsoConfigForm form) {
        String error = ssoConfigService.validate(form);
        if (error != null) {
            return Result.error(error);
        }
        try {
            return Result.success(ssoConfigService.save(form));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @PostMapping("/test/validate")
    public Result<Map<String, Object>> testValidate(@RequestBody SsoTestRequest request) {
        if (request == null || request.getConfig() == null) {
            return Result.error("配置不能为空");
        }
        SsoConfig config = ssoConfigService.resolveForTest(request.getConfig());
        String ticket = request.getTicket();
        if (SsoConfigService.isBlank(ticket)) {
            SsoTicketClient.ProbeResult probe = ssoTicketClient.probe(config);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", probe.reachable());
            data.put("message", probe.message());
            return Result.success(data);
        }
        try {
            SsoAuthService.TestIdentity identity = ssoAuthService.inspectTicket(config, ticket);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("success", true);
            data.put("message", "票据校验通过");
            data.put("username", identity.username());
            data.put("role", identity.role());
            return Result.success(data);
        } catch (SsoLoginException e) {
            return Result.success(failure(e.getMessage()));
        } catch (RuntimeException e) {
            return Result.success(failure("票据校验失败"));
        }
    }

    /** 检查当前请求头（只返回是否存在/是否匹配/解析结果，不回显头值或密钥）。 */
    @PostMapping("/test/headers")
    public Result<Map<String, Object>> testHeaders(
            @RequestBody(required = false) SsoConfigForm form,
            HttpServletRequest request) {
        SsoConfig config = form == null ? ssoConfigService.getRaw() : ssoConfigService.resolveForTest(form);
        String remoteAddr = request.getRemoteAddr();
        boolean trusted = trustedProxyMatcher.isTrusted(remoteAddr);

        String usernameHeaderValue = SsoConfigService.isBlank(config.getUserHeader())
                ? null : request.getHeader(config.getUserHeader());
        String adminHeaderValue = SsoConfigService.isBlank(config.getAdminHeader())
                ? null : request.getHeader(config.getAdminHeader());
        String secretHeaderValue = SsoConfigService.isBlank(config.getSecretHeaderName())
                ? null : request.getHeader(config.getSecretHeaderName());

        boolean secretMatches;
        if (SsoConfigService.isBlank(config.getSecretHeaderName())) {
            secretMatches = true;
        } else {
            secretMatches = SsoConfigService.constantTimeEquals(
                    secretHeaderValue, config.getSecretHeaderValue());
        }

        boolean adminMarker = adminHeaderValue != null
                && !SsoConfigService.isBlank(config.getAdminValue())
                && config.getAdminValue().equalsIgnoreCase(adminHeaderValue);

        String username = null;
        String role = null;
        String message = null;
        if (!SsoConfigService.isBlank(usernameHeaderValue)) {
            try {
                username = SsoAuthService.sanitizeUsername(usernameHeaderValue);
                role = ssoAuthService.resolveRole(config, username, adminMarker);
            } catch (SsoLoginException e) {
                message = e.getMessage();
            }
        }
        if (!trusted) {
            message = "直连地址不在受信代理列表（app.security.trusted-proxies）中，网关身份不会被信任";
        } else if (!secretMatches) {
            message = "共享密钥缺失或不匹配";
        } else if (message == null && username == null) {
            message = "当前请求没有携带用户名头";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remoteAddr", remoteAddr);
        data.put("trusted", trusted);
        data.put("usernameHeaderPresent", usernameHeaderValue != null);
        data.put("adminHeaderPresent", adminHeaderValue != null);
        data.put("secretHeaderPresent", secretHeaderValue != null);
        data.put("secretMatches", secretMatches);
        data.put("username", username);
        data.put("resolvedRole", role);
        if (message != null) {
            data.put("message", message);
        }
        return Result.success(data);
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", false);
        data.put("message", message);
        return data;
    }
}
