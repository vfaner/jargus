package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gitee 地址推导与 v5 release JSON 解析（不联网）。 */
class GiteeReleaseFeedTest {

    @Test
    void derivesApiAndBrowserUrls() {
        String[] both = GiteeReleaseFeed.toApiBase("https://gitee.com/super_rgh/jargus.git");
        assertEquals("https://gitee.com/api/v5/repos/super_rgh/jargus", both[0]);
        assertEquals("https://gitee.com/super_rgh/jargus", both[1]);

        String[] ssh = GiteeReleaseFeed.toApiBase("git@gitee.com:super_rgh/jargus.git");
        assertEquals("https://gitee.com/api/v5/repos/super_rgh/jargus", ssh[0]);
    }

    @Test
    void rejectsUnsupportedRepositoryUrls() {
        assertThrows(IllegalArgumentException.class, () -> GiteeReleaseFeed.toApiBase(""));
        assertThrows(IllegalArgumentException.class, () -> GiteeReleaseFeed.toApiBase("https://github.com/a/b"));
    }

    @Test
    void parsesAssetsOffsetTimeAndFallbackHtmlUrl() throws Exception {
        String json = """
                {
                  "tag_name": "v2.0.7",
                  "created_at": "2026-10-20T12:00:00+08:00",
                  "body": "notes",
                  "assets": [
                    {
                      "name": "jargus.jar",
                      "browser_download_url": "https://gitee.com/super_rgh/jargus/releases/download/v2.0.7/jargus.jar",
                      "size": 456,
                      "created_at": "2026-10-21T08:00:00+08:00"
                    },
                    {
                      "name": "jargus-2.0.7.jar",
                      "download_url": "https://gitee.com/super_rgh/jargus/releases/download/v2.0.7/jargus-2.0.7.jar"
                    }
                  ]
                }
                """;
        ReleaseFeed.Release release = GiteeReleaseFeed.parse(json, "https://gitee.com/super_rgh/jargus");

        assertEquals("v2.0.7", release.tag());
        assertEquals(ReleaseFeed.Source.GITEE, release.source());
        assertEquals(Instant.parse("2026-10-20T04:00:00Z"), release.publishedAt());
        assertEquals("https://gitee.com/super_rgh/jargus/releases/tag/v2.0.7", release.htmlUrl());

        ReleaseFeed.Asset jar = release.jarAsset();
        assertEquals(456L, jar.sizeBytes());
        assertNull(jar.sha256(), "Gitee 不提供摘要");
        assertEquals("size:456", jar.identity());

        ReleaseFeed.Asset versioned = release.assets().get(1);
        assertEquals("https://gitee.com/super_rgh/jargus/releases/download/v2.0.7/jargus-2.0.7.jar",
                versioned.url(), "browser_download_url 缺失时回退 download_url");
        assertNull(versioned.sizeBytes());
    }

    @Test
    void encodesTagInFallbackHtmlUrl() throws Exception {
        String json = "{\"tag_name\":\"v1.0 beta\"}";
        ReleaseFeed.Release release = GiteeReleaseFeed.parse(json, "https://gitee.com/a/b");
        assertTrue(release.htmlUrl().endsWith("/releases/tag/v1.0%20beta"));
    }

    @Test
    void keepsExplicitHtmlUrlAndAllowsMissingAssets() throws Exception {
        String json = """
                {"tag_name":"v1","html_url":"https://gitee.com/a/b/releases/tag/v1"}
                """;
        ReleaseFeed.Release release = GiteeReleaseFeed.parse(json, "https://gitee.com/a/b");
        assertEquals("https://gitee.com/a/b/releases/tag/v1", release.htmlUrl());
        assertNull(release.jarAsset());
    }

    @Test
    void rejectsResponseWithoutTag() {
        assertThrows(IllegalStateException.class,
                () -> GiteeReleaseFeed.parse("{\"body\":\"x\"}", "https://gitee.com/a/b"));
    }
}
