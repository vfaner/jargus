package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安装包比对：摘要优先、GitHub 大小其次、HEAD 兜底（Gitee size 忽略），非可信 URL/exploded 启动一律 UNKNOWN。 */
class JarArtifactComparisonTest {

    private static final String GITHUB_ASSET =
            "https://github.com/vfaner/jargus/releases/download/v2.0.7/jargus.jar";

    @TempDir
    Path tempDir;

    @Test
    void digestMismatchIsDifferent() throws Exception {
        Path jar = jarWithBytes("abc");
        String localSha = JarArtifactComparison.sha256(jar);
        String remoteSha = localSha.equals("0".repeat(64)) ? "1".repeat(64) : "0".repeat(64);

        ArtifactComparison.Comparison result =
                compare(jar, asset(remoteSha, null), url -> null);

        assertEquals(ArtifactComparison.Result.DIFFERENT, result.result());
        assertEquals("sha256:" + remoteSha, result.remoteIdentity());
    }

    @Test
    void digestMatchIsSame() throws Exception {
        Path jar = jarWithBytes("abc");
        String localSha = JarArtifactComparison.sha256(jar);

        ArtifactComparison.Comparison result =
                compare(jar, asset(localSha, null), url -> null);

        assertEquals(ArtifactComparison.Result.SAME, result.result());
        assertEquals("sha256:" + localSha, result.remoteIdentity());
    }

    @Test
    void assetSizeIsComparedBeforeProbing() throws Exception {
        Path jar = jarWithBytes("abc");
        long size = Files.size(jar);

        ArtifactComparison.Comparison same = compare(jar, asset(null, size), url -> {
            throw new AssertionError("有 size 时不应发 HEAD 请求");
        });
        assertEquals(ArtifactComparison.Result.SAME, same.result());
        assertEquals("size:" + size, same.remoteIdentity());

        ArtifactComparison.Comparison different =
                compare(jar, asset(null, size + 1), url -> null);
        assertEquals(ArtifactComparison.Result.DIFFERENT, different.result());
        assertEquals("size:" + (size + 1), different.remoteIdentity());
    }

    @Test
    void giteeAssetSizeIsIgnored() throws Exception {
        Path jar = jarWithBytes("abc");
        long size = Files.size(jar);
        ReleaseFeed.Release gitee = new ReleaseFeed.Release("v2.0.7",
                "https://gitee.com/super_rgh/jargus/releases/tag/v2.0.7", null, "",
                List.of(new ReleaseFeed.Asset("jargus.jar",
                        "https://gitee.com/super_rgh/jargus/releases/download/v2.0.7/jargus.jar",
                        size, null)), ReleaseFeed.Source.GITEE);

        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compareRelease(jar, gitee, url -> null).result(),
                "Gitee 的 size 不参与比较，HEAD 失败即 UNKNOWN");
        assertEquals(ArtifactComparison.Result.SAME,
                compareRelease(jar, gitee, url -> size).result());
    }

    @Test
    void fallsBackToHeadContentLength() throws Exception {
        Path jar = jarWithBytes("abcd");
        long size = Files.size(jar);

        ArtifactComparison.Comparison result = compare(jar, asset(null, null), url -> size);

        assertEquals(ArtifactComparison.Result.SAME, result.result());
        assertEquals("size:" + size, result.remoteIdentity());
    }

    @Test
    void headFailureOrMissingLengthIsUnknown() throws Exception {
        Path jar = jarWithBytes("abc");
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compare(jar, asset(null, null), url -> null).result());
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compare(jar, asset(null, null), url -> 0L).result());
    }

    @Test
    void missingJarIsUnknown() {
        ReleaseFeed.Asset asset = asset("a".repeat(64), null);
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compare(Path.of("/definitely/not/a/jar"), asset, url -> null).result());
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compare(null, asset, url -> null).result());
    }

    @Test
    void untrustedUrlOrDisabledIsUnknown() throws Exception {
        Path jar = jarWithBytes("abc");
        ReleaseFeed.Release evil = release(new ReleaseFeed.Asset(
                "jargus.jar", "http://github.com/a/jargus.jar", 3L));
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compareRelease(jar, evil, url -> null).result());

        JarArtifactComparison disabled = new JarArtifactComparison(() -> jar, false, url -> null);
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                disabled.compare(release(asset(null, 3L))).result());
    }

    @Test
    void releaseWithoutJarAssetIsUnknown() throws Exception {
        Path jar = jarWithBytes("abc");
        ReleaseFeed.Release noJar = release(new ReleaseFeed.Asset(
                "notes.txt", GITHUB_ASSET, 3L));
        assertEquals(ArtifactComparison.Result.UNKNOWN,
                compareRelease(jar, noJar, url -> null).result());
        assertNull(ArtifactComparison.Comparison.unknown().remoteIdentity());
    }

    @Test
    void onlyHttpsGithubAndGiteeDownloadsAreTrusted() {
        assertTrue(JarArtifactComparison.isTrustedDownloadUrl(GITHUB_ASSET));
        assertTrue(JarArtifactComparison.isTrustedDownloadUrl(
                "https://gitee.com/super_rgh/jargus/releases/download/v2.0.7/jargus.jar"));
        assertFalse(JarArtifactComparison.isTrustedDownloadUrl(
                "http://github.com/a/b/jargus.jar"));
        assertFalse(JarArtifactComparison.isTrustedDownloadUrl(
                "https://evil.example.com/jargus.jar"));
        assertFalse(JarArtifactComparison.isTrustedDownloadUrl(null));
    }

    private Path jarWithBytes(String content) throws Exception {
        Path jar = tempDir.resolve("jargus.jar");
        Files.writeString(jar, content);
        return jar;
    }

    private static ReleaseFeed.Asset asset(String sha256, Long size) {
        return new ReleaseFeed.Asset("jargus.jar", GITHUB_ASSET, size, sha256);
    }

    private static ReleaseFeed.Release release(ReleaseFeed.Asset asset) {
        return new ReleaseFeed.Release("v2.0.7",
                "https://github.com/vfaner/jargus/releases/tag/v2.0.7", null, "",
                List.of(asset), ReleaseFeed.Source.GITHUB);
    }

    private static ArtifactComparison.Comparison compare(Path jar, ReleaseFeed.Asset asset,
                                                          JarArtifactComparison.SizeProbe probe) {
        return compareRelease(jar, release(asset), probe);
    }

    private static ArtifactComparison.Comparison compareRelease(
            Path jar, ReleaseFeed.Release release, JarArtifactComparison.SizeProbe probe) {
        return new JarArtifactComparison(() -> jar, true, probe).compare(release);
    }
}
