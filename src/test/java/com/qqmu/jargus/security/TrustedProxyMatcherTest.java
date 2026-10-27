package com.qqmu.jargus.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedProxyMatcherTest {

    @Test
    void exactIpv4AndIpv6() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of("127.0.0.1", "::1"));
        assertTrue(matcher.isTrusted("127.0.0.1"));
        assertTrue(matcher.isTrusted("::1"));
        assertFalse(matcher.isTrusted("127.0.0.2"));
        assertFalse(matcher.isTrusted("10.0.0.1"));
    }

    @Test
    void cidrRanges() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of("10.1.0.0/16"));
        assertTrue(matcher.isTrusted("10.1.2.3"));
        assertTrue(matcher.isTrusted("10.1.255.255"));
        assertFalse(matcher.isTrusted("10.2.0.1"));
        assertFalse(matcher.isTrusted("::1"));
    }

    @Test
    void ipv4MappedIpv6IsNormalized() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of("127.0.0.1"));
        assertTrue(matcher.isTrusted("::ffff:127.0.0.1"));
    }

    @Test
    void hostnamesAndGarbageAreNeverTrusted() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of("127.0.0.1"));
        assertFalse(matcher.isTrusted("localhost"));
        assertFalse(matcher.isTrusted("evil.example.com"));
        assertFalse(matcher.isTrusted(""));
        assertFalse(matcher.isTrusted(null));
    }

    @Test
    void emptyConfigurationTrustsNothing() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of());
        assertTrue(matcher.configuredRules().isEmpty());
        assertFalse(matcher.isTrusted("127.0.0.1"));
    }

    @Test
    void invalidRuleFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedProxyMatcher(List.of("not-an-ip")));
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedProxyMatcher(List.of("10.0.0.0/33")));
    }

    @Test
    void keepsConfiguredRulesForDisplay() {
        TrustedProxyMatcher matcher = new TrustedProxyMatcher(List.of(" 10.0.0.0/8 ", "::1"));
        assertEquals(List.of("10.0.0.0/8", "::1"), matcher.configuredRules());
    }
}
