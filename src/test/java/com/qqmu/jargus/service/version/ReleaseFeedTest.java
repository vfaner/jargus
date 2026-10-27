package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发布源公共契约：限流异常分类、附件身份、时间解析。 */
class ReleaseFeedTest {

    @Test
    void forbiddenAndTooManyRequestsAreRateLimited() {
        Exception forbidden = ReleaseFeed.apiFailure(ReleaseFeed.Source.GITHUB, 403);
        assertInstanceOf(ReleaseFeed.RateLimitedException.class, forbidden);
        ReleaseFeed.RateLimitedException rate = (ReleaseFeed.RateLimitedException) forbidden;
        assertEquals(ReleaseFeed.Source.GITHUB, rate.source());
        assertEquals(403, rate.statusCode());
        assertTrue(rate.getMessage().contains("GitHub"));

        assertInstanceOf(ReleaseFeed.RateLimitedException.class,
                ReleaseFeed.apiFailure(ReleaseFeed.Source.GITEE, 429));
    }

    @Test
    void otherStatusCodesCarrySourceAndCode() {
        Exception failure = ReleaseFeed.apiFailure(ReleaseFeed.Source.GITEE, 500);
        assertInstanceOf(IllegalStateException.class, failure);
        assertTrue(failure.getMessage().contains("Gitee"));
        assertTrue(failure.getMessage().contains("500"));
    }

    @Test
    void identityPrefersSha256ThenSize() {
        ReleaseFeed.Asset withDigest = new ReleaseFeed.Asset(
                "jargus.jar", "https://github.com/a/b.jar", 10L, "ABC123");
        assertEquals("sha256:abc123", withDigest.identity());

        ReleaseFeed.Asset sizeOnly = new ReleaseFeed.Asset(
                "jargus.jar", "https://github.com/a/b.jar", 42L);
        assertEquals("size:42", sizeOnly.identity());

        ReleaseFeed.Asset neither = new ReleaseFeed.Asset(
                "jargus.jar", "https://github.com/a/b.jar", null);
        assertNull(neither.identity());
    }

    @Test
    void assetNameLookupIsCaseInsensitiveAndFallsBackToVersionedName() {
        ReleaseFeed.Release exact = new ReleaseFeed.Release("v1", "https://github.com/a/b", null, "",
                java.util.List.of(
                        new ReleaseFeed.Asset("jargus-2.0.6.jar", "https://github.com/v.jar", 1L),
                        new ReleaseFeed.Asset("JARGUS.JAR", "https://github.com/j.jar", 2L)),
                ReleaseFeed.Source.GITHUB);
        assertEquals("https://github.com/j.jar", exact.jarAsset().url());

        ReleaseFeed.Release versioned = new ReleaseFeed.Release("v1", "https://github.com/a/b", null, "",
                java.util.List.of(
                        new ReleaseFeed.Asset("other.zip", "https://github.com/o.zip", 1L),
                        new ReleaseFeed.Asset("jargus-2.0.6.jar", "https://github.com/v.jar", 2L)),
                ReleaseFeed.Source.GITHUB);
        assertEquals("https://github.com/v.jar", versioned.jarAsset().url());
    }

    @Test
    void parsesInstantAndOffsetDateTime() {
        assertEquals(Instant.parse("2026-10-20T12:00:00Z"),
                ReleaseFeed.parseInstant("2026-10-20T12:00:00Z"));
        assertEquals(Instant.parse("2026-10-20T04:00:00Z"),
                ReleaseFeed.parseInstant("2026-10-20T12:00:00+08:00"));
        assertNull(ReleaseFeed.parseInstant("not-a-time"));
        assertNull(ReleaseFeed.parseInstant(""));
    }
}
