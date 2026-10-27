package com.qqmu.jargus.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * 客户端 IP 解析（仅用于登录失败限流）。
 *
 * <p>只有直连 peer 本身是受信代理时才读取 {@code X-Forwarded-For}/{@code X-Real-IP}；
 * 不受信来源伪造的转发头一律忽略。网关头认证的信任判断不使用本类。
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private final TrustedProxyMatcher trustedProxyMatcher;

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) {
            return "";
        }
        if (!trustedProxyMatcher.isTrusted(peer)) {
            return peer;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            for (String part : forwardedFor.split(",")) {
                String candidate = canonical(part.trim());
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        String realIp = canonical(request.getHeader("X-Real-IP"));
        return realIp != null ? realIp : peer;
    }

    /**
     * 合法 IP 归一成标准文本（IPv4-mapped IPv6 转 IPv4），
     * 避免 {@code ::ffff:1.2.3.4} 与 {@code 1.2.3.4} 被算成两个限流 key。
     */
    private static String canonical(String value) {
        InetAddress address = TrustedProxyMatcher.parseIp(value);
        return address == null ? null : address.getHostAddress();
    }
}
