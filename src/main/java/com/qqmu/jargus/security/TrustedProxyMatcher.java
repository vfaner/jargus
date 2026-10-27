package com.qqmu.jargus.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 受信代理地址匹配：只接受 IP 字面量或 CIDR，不解析主机名（避免 DNS 参与信任判断）。
 *
 * <p>网关头认证只信任直连 peer 地址；默认仅回环地址。网关在另一台机器/容器时，
 * 必须把它的出口 IP 或网段加入 {@code app.security.trusted-proxies}。
 */
@Component
public class TrustedProxyMatcher {

    private final List<String> configuredRules;
    private final List<Rule> rules;

    @Autowired
    public TrustedProxyMatcher(
            @Value("${app.security.trusted-proxies:127.0.0.1,::1}") String configured) {
        this(splitRules(configured));
    }

    TrustedProxyMatcher(List<String> configuredRules) {
        List<String> normalized = new ArrayList<>();
        if (configuredRules != null) {
            for (String raw : configuredRules) {
                if (raw != null && !raw.trim().isEmpty()) {
                    normalized.add(raw.trim());
                }
            }
        }
        this.configuredRules = List.copyOf(normalized);
        this.rules = parseRules(String.join(",", normalized));
    }

    private static List<String> splitRules(String configured) {
        List<String> items = new ArrayList<>();
        if (configured == null) {
            return items;
        }
        for (String raw : configured.split(",")) {
            String item = raw.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }

    public boolean isTrusted(String remoteAddr) {
        InetAddress peer = parseIp(remoteAddr);
        if (peer == null) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(peer)) {
                return true;
            }
        }
        return false;
    }

    public List<String> configuredRules() {
        return configuredRules;
    }

    /** 合法的 IPv4/IPv6 字面量（允许 IPv6 zone 后缀），不是主机名。 */
    static InetAddress parseIp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone);
        }
        if (text.isEmpty() || !(text.contains(":") || looksLikeIpv4(text))) {
            return null;
        }
        try {
            return normalize(InetAddress.getByName(text));
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean looksLikeIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** IPv4-mapped IPv6（::ffff:a.b.c.d）统一成 IPv4，避免同一地址两种表示漏判。 */
    private static InetAddress normalize(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return address;
        }
        byte[] bytes = address.getAddress();
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                mapped = false;
                break;
            }
        }
        if (mapped && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            try {
                return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
            } catch (UnknownHostException e) {
                return address;
            }
        }
        return address;
    }

    private static List<Rule> parseRules(String configured) {
        List<Rule> parsed = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return parsed;
        }
        for (String raw : configured.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            parsed.add(parseRule(item));
        }
        return parsed;
    }

    private static Rule parseRule(String item) {
        int slash = item.indexOf('/');
        String addressPart = slash >= 0 ? item.substring(0, slash) : item;
        InetAddress network = parseIp(addressPart);
        if (network == null) {
            throw new IllegalArgumentException("受信代理配置必须是 IP 或 CIDR: " + item);
        }
        int maxBits = network instanceof Inet4Address ? 32 : 128;
        int prefix = maxBits;
        if (slash >= 0) {
            String prefixText = item.substring(slash + 1).trim();
            try {
                prefix = Integer.parseInt(prefixText);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("受信代理 CIDR 前缀不合法: " + item);
            }
            if (prefix < 0 || prefix > maxBits) {
                throw new IllegalArgumentException("受信代理 CIDR 前缀超出范围: " + item);
            }
        }
        return new Rule(network.getAddress(), prefix);
    }

    private static final class Rule {
        private final byte[] network;
        private final int prefix;

        private Rule(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        private boolean matches(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefix / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefix % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
