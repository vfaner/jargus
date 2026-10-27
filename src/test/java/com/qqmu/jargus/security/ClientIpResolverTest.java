package com.qqmu.jargus.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    private TrustedProxyMatcher matcher;
    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        matcher = mock(TrustedProxyMatcher.class);
        resolver = new ClientIpResolver(matcher);
    }

    @Test
    void untrustedPeerIgnoresForwardedHeaders() {
        when(matcher.isTrusted("203.0.113.9")).thenReturn(false);
        MockHttpServletRequest request = request("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertEquals("203.0.113.9", resolver.resolve(request));
    }

    @Test
    void trustedPeerUsesFirstValidForwardedAddress() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.1");

        assertEquals("1.2.3.4", resolver.resolve(request));
    }

    @Test
    void trustedPeerSkipsInvalidForwardedEntries() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "not-an-ip, 5.6.7.8");

        assertEquals("5.6.7.8", resolver.resolve(request));
    }

    @Test
    void trustedPeerFallsBackToRealIp() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "garbage");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertEquals("5.6.7.8", resolver.resolve(request));
    }

    @Test
    void trustedPeerFallsBackToPeerWhenHeadersInvalid() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "garbage");
        request.addHeader("X-Real-IP", "also-garbage");

        assertEquals("127.0.0.1", resolver.resolve(request));
    }

    @Test
    void trustedPeerPrefersForwardedAddressOverRealIp() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("X-Real-IP", "5.6.7.8");

        assertEquals("1.2.3.4", resolver.resolve(request));
    }

    @Test
    void normalizesIpv4MappedForwardedAddress() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Forwarded-For", "::ffff:1.2.3.4");

        assertEquals("1.2.3.4", resolver.resolve(request));
    }

    @Test
    void normalizesIpv4MappedRealIp() {
        when(matcher.isTrusted("127.0.0.1")).thenReturn(true);
        MockHttpServletRequest request = request("127.0.0.1");
        request.addHeader("X-Real-IP", "::ffff:5.6.7.8");

        assertEquals("5.6.7.8", resolver.resolve(request));
    }

    @Test
    void blankPeerReturnsEmptyKey() {
        assertEquals("", resolver.resolve(request("")));
        assertEquals("", resolver.resolve(request(null)));
    }

    private static MockHttpServletRequest request(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }
}
