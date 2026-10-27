package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub 仓库地址推导与 release JSON 解析（不联网）。 */
class GithubReleaseFeedTest {

    @Test
    void derivesApiBaseFromBrowserAndSshUrls() {
        assertEquals("https://api.github.com/repos/vfaner/jargus",
                GithubReleaseFeed.toApiBase("https://github.com/vfaner/jargus"));
        assertEquals("https://api.github.com/repos/vfaner/jargus",
                GithubReleaseFeed.toApiBase("https://github.com/vfaner/jargus.git/"));
        assertEquals("https://api.github.com/repos/vfaner/jargus",
                GithubReleaseFeed.toApiBase("git@github.com:vfaner/jargus.git"));
    }

    @Test
    void rejectsUnsupportedRepositoryUrls() {
        assertThrows(IllegalArgumentException.class, () -> GithubReleaseFeed.toApiBase(""));
        assertThrows(IllegalArgumentException.class, () -> GithubReleaseFeed.toApiBase("https://gitlab.com/a/b"));
        assertThrows(IllegalArgumentException.class, () -> GithubReleaseFeed.toApiBase("https://github.com/a"));
    }

    @Test
    void parsesAssetsDigestAndUpdatedAtFallback() throws Exception {
        String json = """
                {
                  "tag_name": "v2.0.7",
                  "html_url": "https://github.com/vfaner/jargus/releases/tag/v2.0.7",
                  "published_at": "2026-10-20T08:00:00Z",
                  "body": "notes",
                  "assets": [
                    {
                      "name": "jargus.jar",
                      "browser_download_url": "https://github.com/vfaner/jargus/releases/download/v2.0.7/jargus.jar",
                      "size": 123,
                      "digest": "sha256:ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
                      "updated_at": "2026-10-21T09:30:00Z"
                    },
                    {
                      "name": "notes.txt",
                      "browser_download_url": "https://github.com/vfaner/jargus/releases/download/v2.0.7/notes.txt",
                      "size": 9,
                      "digest": "md5:0011",
                      "created_at": "2026-10-20T10:00:00Z"
                    }
                  ]
                }
                """;
        ReleaseFeed.Release release = GithubReleaseFeed.parse(json);

        assertEquals("v2.0.7", release.tag());
        assertEquals(ReleaseFeed.Source.GITHUB, release.source());
        assertEquals(Instant.parse("2026-10-20T08:00:00Z"), release.publishedAt());
        assertEquals(2, release.assets().size());

        ReleaseFeed.Asset jar = release.jarAsset();
        assertEquals(123L, jar.sizeBytes());
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                jar.sha256());
        assertEquals(Instant.parse("2026-10-21T09:30:00Z"), jar.updatedAt());

        ReleaseFeed.Asset notes = release.assets().get(1);
        assertNull(notes.sha256(), "只接受 sha256 摘要");
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), notes.updatedAt(),
                "updated_at 缺失时回退 created_at");
    }

    @Test
    void skipsAssetsWithoutNameOrDownloadUrl() throws Exception {
        String json = """
                {
                  "tag_name": "v1",
                  "html_url": "https://github.com/a/b/releases/tag/v1",
                  "assets": [
                    {"name": "nameless.jar"},
                    {"browser_download_url": "https://github.com/a/b.jar"}
                  ]
                }
                """;
        ReleaseFeed.Release release = GithubReleaseFeed.parse(json);
        assertTrue(release.assets().isEmpty());
    }

    @Test
    void rejectsResponseWithoutTag() {
        assertThrows(IllegalStateException.class,
                () -> GithubReleaseFeed.parse("{\"html_url\":\"https://github.com/a/b\"}"));
    }

    @Test
    void normalizesOnlySha256Digests() {
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                GithubReleaseFeed.normalizeSha256(
                        "sha256:ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789"));
        assertNull(GithubReleaseFeed.normalizeSha256("sha256:ABCDEF"));
        assertNull(GithubReleaseFeed.normalizeSha256("md5:abcdef"));
        assertNull(GithubReleaseFeed.normalizeSha256("sha256:xyz"));
        assertNull(GithubReleaseFeed.normalizeSha256(null));
    }
}
