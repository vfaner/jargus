package com.qqmu.jargus.security;

import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器
 *
 * token 有两个来源：Authorization: Bearer 头（/api 调用）和 httpOnly 的
 * {@value #TOKEN_COOKIE} Cookie（Thymeleaf 页面导航 + 同源 fetch）。
 * - /api/** 未认证 → 401 JSON
 * - 页面导航（GET 非 /api）未认证 → 302 跳 /login?redirect=...
 * - 白名单（登录接口、静态资源、webhook、actuator）直接放行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String TOKEN_COOKIE = "jargus_token";

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;

    @Value("${app.auth-enabled:true}")
    private boolean authEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        log.debug("JwtAuthFilter: {} {}", method, path);

        // 认证禁用时，给一个默认 admin 上下文（方便开发/演示）
        if (!authEnabled) {
            UserContext.set(new UserContext.CurrentUser(
                    1L, "admin", "管理员", "ADMIN", "LOCAL", false
            ));
            try {
                filterChain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
            return;
        }

        // 公开接口：登录 / 登出 / 登录页远端配置列表
        if (isPublicEndpoint(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 静态资源、H2 控制台、actuator 等直接放行
        if (isPublicResource(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 登录页本身不需要登录态
        if ("GET".equalsIgnoreCase(method) && "/login".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractToken(request);
        Long userId = null;
        if (token != null && jwtUtil.validateToken(token)) {
            userId = jwtUtil.getUserId(token);
        }

        SysUser user = null;
        if (userId != null) {
            SysUser u = sysUserMapper.selectById(userId);
            if (u != null && Boolean.TRUE.equals(u.getIsEnabled())) {
                user = u;
            }
        }

        if (user == null) {
            if (path.startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
            } else {
                // 页面导航未登录 → 登录页，登录后回跳
                String here = path + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
                response.sendRedirect("/login?redirect=" +
                        URLEncoder.encode(here, StandardCharsets.UTF_8));
            }
            return;
        }

        UserContext.set(new UserContext.CurrentUser(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getRole(),
                user.getSource(),
                Boolean.TRUE.equals(user.getIsPasswordDefault())
        ));

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (TOKEN_COOKIE.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                    return c.getValue();
                }
            }
        }
        // 不支持 query 参数取 token：?token= 会进访问日志、Referer 与浏览器历史，
        // 下载/预览链接走 httpOnly Cookie（SameSite=Lax 顶层导航同样携带），无需 query 通道
        return null;
    }

    private boolean isPublicEndpoint(String path, String method) {
        if (path == null) return false;
        if ("POST".equalsIgnoreCase(method)
                && (path.equals("/api/auth/login")
                || path.equals("/api/auth/remote-login")
                || path.equals("/api/auth/logout"))) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && path.equals("/api/auth/remote-configs")) {
            return true;
        }
        // Webhook 自带签名鉴权
        return path.startsWith("/api/ci/webhook/");
    }

    private boolean isPublicResource(String path) {
        if (path == null) return false;
        // 后缀白名单只适用于静态资源：/api/** 一律不走后缀豁免，
        // 否则将来任何以 .js/.css/.png 结尾的接口都会变成免登录
        if (path.startsWith("/api/")) return false;
        if (path.startsWith("/static/")) return true;
        if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".png")
                || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp")
                || path.endsWith(".ico") || path.endsWith(".svg") || path.endsWith(".gif")
                || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".map")) {
            return true;
        }
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info")) {
            return true;
        }
        // 网站图标
        return "/favicon.ico".equals(path) || "/favicon.svg".equals(path);
    }

    /** 登录成功后下发 httpOnly Cookie，供服务端渲染页面鉴权 */
    public static ResponseCookie authCookie(String token, long expireSeconds) {
        return ResponseCookie.from(TOKEN_COOKIE, token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(expireSeconds)
                .build();
    }

    /** 登出时清除 Cookie */
    public static ResponseCookie clearAuthCookie() {
        return ResponseCookie.from(TOKEN_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
