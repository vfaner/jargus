package com.qqmu.jargus.util;

import java.util.Locale;

/**
 * 统一认证票据/网关头取值的宽松解析工具。
 */
public final class JsonFieldUtils {

    private JsonFieldUtils() {
    }

    /** 值转文本；null 返回 null，布尔/数字用标准 toString。 */
    public static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        return null;
    }

    /** 成功标志比较：忽略大小写与首尾空白；Boolean true 可匹配 "true"。 */
    public static boolean matches(Object actual, String expected) {
        String text = asText(actual);
        if (text == null) {
            return false;
        }
        String want = expected == null ? "" : expected.trim();
        return text.trim().equalsIgnoreCase(want);
    }

    /** 管理员标记：true/1/yes/admin/administrator（忽略大小写），数字非 0 也算真。 */
    public static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        String text = asText(value);
        if (text == null) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return "true".equals(lower) || "1".equals(lower) || "yes".equals(lower)
                || "admin".equals(lower) || "administrator".equals(lower);
    }
}
