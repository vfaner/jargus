package com.qqmu.jargus.security;

import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.service.SsoAuthService;
import com.qqmu.jargus.service.SsoConfigService;
import com.qqmu.jargus.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 受信网关身份头认证。
 *
 * <p>只有在直连 peer 命中 {@code app.security.trusted-proxies} 时才信任
 * {@code X-Auth-User} 等头；绝不根据客户端可伪造的 X-Forwarded-For 决定信任。
 * 配置了共享密钥时还必须常量时间比对通过。失败一律按未认证处理，不泄漏细节。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
public class SsoHeaderAuthFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_USER_ATTRIBUTE =
            SsoHeaderAuthFilter.class.getName() + ".AUTHENTICATED_USER";

    private final SsoConfigService ssoConfigService;
    private final SsoAuthService ssoAuthService;
    private final TrustedProxyMatcher trustedProxyMatcher;
    private final JwtUtil jwtUtil;

    @Value("${app.auth-enabled:true}")
    private boolean authEnabled;

    @Value("${app.jwt-expire-hours:24}")
    private int jwtExpireHours;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!authEnabled) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (JwtAuthFilter.isPublicEndpoint(path, method)
                || JwtAuthFilter.isPublicResource(path)
                || ("GET".equalsIgnoreCase(method) && "/login".equals(path))) {
            chain.doFilter(request, response);
            return;
        }

        // 已有有效登录态：不重复认证，角色等 token 过期后再刷新（放在读配置前，省一次查库）
        String token = JwtAuthFilter.extractToken(request);
        if (token != null && jwtUtil.validateToken(token)) {
            chain.doFilter(request, response);
            return;
        }

        SsoConfig config = ssoConfigService.getRaw();
        if (!Boolean.TRUE.equals(config.getIsEnabled())
                || !SsoConfig.MODE_HEADER.equals(SsoConfigService.upper(config.getAuthMode()))) {
            chain.doFilter(request, response);
            return;
        }

        if (!trustedProxyMatcher.isTrusted(request.getRemoteAddr())) {
            chain.doFilter(request, response);
            return;
        }
        if (!secretMatches(request, config)) {
            chain.doFilter(request, response);
            return;
        }

        String usernameHeader = config.getUserHeader();
        if (SsoConfigService.isBlank(usernameHeader)) {
            chain.doFilter(request, response);
            return;
        }
        String username = request.getHeader(usernameHeader);
        if (SsoConfigService.isBlank(username)) {
            chain.doFilter(request, response);
            return;
        }

        boolean adminMarker = !SsoConfigService.isBlank(config.getAdminHeader())
                && !SsoConfigService.isBlank(config.getAdminValue())
                && config.getAdminValue().equalsIgnoreCase(request.getHeader(config.getAdminHeader()));

        SsoAuthService.SsoLoginResult result;
        try {
            result = ssoAuthService.authenticateHeader(config, username, adminMarker);
        } catch (SsoLoginException e) {
            log.info("网关头认证失败: {}", e.getMessage());
            reject(request, response, e.getMessage());
            return;
        } catch (RuntimeException e) {
            log.warn("网关头认证异常: {}", e.getClass().getSimpleName());
            reject(request, response, "网关头身份认证失败");
            return;
        }

        SysUser user = result.user();
        long seconds = jwtExpireHours * 3600L;
        response.addHeader(HttpHeaders.SET_COOKIE, JwtAuthFilter.authCookie(result.token(), seconds).toString());
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private boolean secretMatches(HttpServletRequest request, SsoConfig config) {
        if (SsoConfigService.isBlank(config.getSecretHeaderName())) {
            return true;
        }
        String expected = config.getSecretHeaderValue();
        if (SsoConfigService.isBlank(expected)) {
            // 配置了密钥头但密钥无法解密/未配置：fail closed
            return false;
        }
        String provided = request.getHeader(config.getSecretHeaderName());
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\""
                    + escapeJson(message) + "\",\"data\":null}");
        } else {
            response.sendRedirect("/login?ssoHeaderError");
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
