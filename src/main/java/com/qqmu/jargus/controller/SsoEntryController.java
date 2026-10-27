package com.qqmu.jargus.controller;

import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.security.ClientIpResolver;
import com.qqmu.jargus.security.JwtAuthFilter;
import com.qqmu.jargus.security.PublicAccess;
import com.qqmu.jargus.security.SsoLoginRateLimiter;
import com.qqmu.jargus.service.SsoAuthService;
import com.qqmu.jargus.service.SsoConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Map;

/**
 * 企业统一认证票据入口。
 *
 * <p>企业登录中心完成登录后，把浏览器重定向到 {@code /sso/entry?ticket=...}。
 * 本端点永远返回 302（成功进仪表盘，失败回登录页带错误码），不返回 JSON/401/500；
 * 票据值不写日志，也不接受任何 {@code next}/{@code redirect} 回跳参数。
 */
@Slf4j
@PublicAccess
@Controller
@RequiredArgsConstructor
public class SsoEntryController {

    static final int MAX_TICKET_LENGTH = 4096;

    private final SsoConfigService ssoConfigService;
    private final SsoAuthService ssoAuthService;
    private final SsoLoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Value("${app.jwt-expire-hours:24}")
    private int jwtExpireHours;

    @GetMapping("/sso/entry")
    public void entry(@RequestParam Map<String, String> params,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        SsoConfig config = ssoConfigService.getRaw();
        if (!Boolean.TRUE.equals(config.getIsEnabled())
                || !SsoConfig.MODE_TICKET.equals(SsoConfigService.upper(config.getAuthMode()))
                || !SsoConfigService.isSsoUsable(config)) {
            response.sendRedirect("/login?ssoDisabled");
            return;
        }

        String clientIp = clientIpResolver.resolve(request);
        if (rateLimiter.isBlocked(clientIp)) {
            response.sendRedirect("/login?ssoTooManyAttempts");
            return;
        }

        String ticket = params.get(config.getTicketParam());
        if (ticket == null || ticket.isBlank()) {
            rateLimiter.recordFailure(clientIp);
            response.sendRedirect("/login?ssoTicketMissing");
            return;
        }
        if (ticket.length() > MAX_TICKET_LENGTH) {
            rateLimiter.recordFailure(clientIp);
            response.sendRedirect("/login?ssoTicketError");
            return;
        }

        try {
            SsoAuthService.SsoLoginResult result = ssoAuthService.loginByTicket(config, ticket);
            rateLimiter.reset(clientIp);
            long seconds = jwtExpireHours * 3600L;
            response.addHeader(HttpHeaders.SET_COOKIE,
                    JwtAuthFilter.authCookie(result.token(), seconds).toString());
            response.sendRedirect("/dashboard");
        } catch (SsoLoginException e) {
            rateLimiter.recordFailure(clientIp);
            log.info("SSO 票据登录失败: {}", e.getMessage());
            response.sendRedirect("/login?ssoTicketError");
        } catch (RuntimeException e) {
            rateLimiter.recordFailure(clientIp);
            log.warn("SSO 票据登录异常: {}", e.getClass().getSimpleName());
            response.sendRedirect("/login?ssoTicketError");
        }
    }
}
