package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本状态判定：新版本、同版本安装包重发、忽略持久化、双源兜底与双栏展示。
 * 全部用内存 feed，不联网；执行器同步（Runnable::run）。
 */
class VersionServiceTest {

    private static final String JAR_URL =
            "https://github.com/vfaner/jargus/releases/download/v2.0.7/jargus.jar";

    @Test
    void newerReleaseIsUpdateAvailable() {
        VersionService service = service(feed(release("v2.0.8", ReleaseFeed.Source.GITHUB)),
                feed(release("v2.0.8", ReleaseFeed.Source.GITEE)),
                neverCalledComparison(), new FakeDismissal(), "2.0.7");

        VersionInfo info = service.snapshot();

        assertEquals(VersionInfo.State.UPDATE_AVAILABLE, info.getState());
        assertEquals("2.0.8", info.getLatestVersion());
        assertEquals("v2.0.8", info.getLatestTag());
        assertEquals("2026-10-20", info.getLatestPublishedDate());
    }

    @Test
    void newerReleaseDoesNotRunArtifactComparison() {
        VersionService service = service(feed(release("v2.0.8", ReleaseFeed.Source.GITHUB)),
                feed(null), neverCalledComparison(), new FakeDismissal(), "2.0.7");

        assertEquals(VersionInfo.State.UPDATE_AVAILABLE, service.snapshot().getState());
    }

    @Test
    void sameVersionWithDifferentArtifactIsArtifactUpdated() {
        AtomicInteger calls = new AtomicInteger();
        ArtifactComparison comparison = release -> {
            calls.incrementAndGet();
            return new ArtifactComparison.Comparison(
                    ArtifactComparison.Result.DIFFERENT, "sha256:new");
        };
        VersionService service = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), comparison, new FakeDismissal(), "2.0.7");

        VersionInfo info = service.snapshot();

        assertEquals(VersionInfo.State.ARTIFACT_UPDATED, info.getState());
        assertEquals("2026-10-21", info.getLatestArtifactDate());
        assertEquals(1, calls.get());
    }

    @Test
    void sameVersionWithSameArtifactIsUpToDate() {
        VersionService service = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.SAME, "sha256:same"),
                new FakeDismissal(), "2.0.7");

        assertEquals(VersionInfo.State.UP_TO_DATE, service.snapshot().getState());
    }

    @Test
    void unknownComparisonAndOlderReleaseAreUpToDate() {
        VersionService unknown = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.UNKNOWN, null),
                new FakeDismissal(), "2.0.7");
        assertEquals(VersionInfo.State.UP_TO_DATE, unknown.snapshot().getState());

        VersionService older = service(feed(release("v2.0.6", ReleaseFeed.Source.GITHUB)),
                feed(null), neverCalledComparison(), new FakeDismissal(), "2.0.7");
        assertEquals(VersionInfo.State.UP_TO_DATE, older.snapshot().getState());
    }

    @Test
    void dismissedArtifactIsNotReportedButNewKeyIs() {
        FakeDismissal dismissed = new FakeDismissal();
        dismissed.key = "sha256:same";

        VersionService suppressed = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.DIFFERENT, "sha256:same"),
                dismissed, "2.0.7");
        assertEquals(VersionInfo.State.UP_TO_DATE, suppressed.snapshot().getState());

        VersionService changed = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.DIFFERENT, "sha256:new"),
                dismissed, "2.0.7");
        assertEquals(VersionInfo.State.ARTIFACT_UPDATED, changed.snapshot().getState());
    }

    @Test
    void dismissArtifactPersistsAndUpdatesSnapshotImmediately() {
        FakeDismissal dismissal = new FakeDismissal();
        VersionService service = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.DIFFERENT, "sha256:new"),
                dismissal, "2.0.7");
        assertEquals(VersionInfo.State.ARTIFACT_UPDATED, service.snapshot().getState());

        assertTrue(service.dismissArtifact());

        assertEquals("sha256:new", dismissal.key);
        assertEquals(VersionInfo.State.UP_TO_DATE, service.snapshot().getState());
    }

    @Test
    void dismissWithoutArtifactStateReturnsFalse() {
        VersionService service = service(feed(release("v2.0.7", ReleaseFeed.Source.GITHUB)),
                feed(null), constantComparison(ArtifactComparison.Result.SAME, "sha256:same"),
                new FakeDismissal(), "2.0.7");
        service.snapshot();
        assertFalse(service.dismissArtifact());
    }

    @Test
    void checkNowFallsBackToGiteeAndReportsArtifactUpdate() {
        FakeFeed github = feed(null);
        github.latestFailure = new IllegalStateException("github down");
        FakeFeed gitee = feed(release("v2.0.7", ReleaseFeed.Source.GITEE));
        VersionService service = new VersionService(
                new ChainedReleaseFeed(github, gitee), gitee,
                constantComparison(ArtifactComparison.Result.DIFFERENT, "sha256:new"),
                new FakeDismissal(), "2.0.7", true, Runnable::run);

        VersionService.UpdateCheck check = service.checkNow();

        assertTrue(check.checked());
        assertTrue(check.updateAvailable());
        assertTrue(check.artifactUpdated());
        assertEquals("2.0.7", check.latestVersion());
        assertEquals("https://gitee.com/super_rgh/jargus/releases/tag/v2.0.7", check.releaseUrl());
    }

    @Test
    void checkNowReturnsUncheckedWhenBothSourcesFail() {
        FakeFeed github = feed(null);
        github.latestFailure = new IllegalStateException("github down");
        FakeFeed gitee = feed(null);
        gitee.latestFailure = new IllegalStateException("gitee down");
        VersionService service = new VersionService(
                new ChainedReleaseFeed(github, gitee), gitee, null, null,
                "2.0.7", true, Runnable::run);

        VersionService.UpdateCheck check = service.checkNow();

        assertFalse(check.checked());
        assertFalse(check.updateAvailable());
        assertNull(check.latestVersion());
    }

    @Test
    void disabledServiceMakesNoRequests() {
        FakeFeed main = feed(release("v2.0.8", ReleaseFeed.Source.GITHUB));
        // 9.9.9 没有内置发布说明；关闭检查时也不应回查远端 byTag
        VersionService service = new VersionService(main, main, null, null,
                "9.9.9", false, Runnable::run);

        assertEquals(VersionInfo.State.DISABLED, service.snapshot().getState());
        assertFalse(service.checkNow().checked());
        assertEquals(0, main.latestCalls.get());
        assertEquals(0, main.byTagCalls.get());
    }

    @Test
    void dualColumnsUseGithubMainAndSeparateGiteeFeed() {
        FakeFeed main = feed(release("v2.0.8", ReleaseFeed.Source.GITHUB));
        FakeFeed gitee = feed(release("v2.0.6", ReleaseFeed.Source.GITEE));
        VersionInfo info = service(main, gitee, neverCalledComparison(),
                new FakeDismissal(), "2.0.7").snapshot();

        assertEquals("2.0.8", info.getLatestVersion());
        assertEquals("GitHub", info.getLatestSourceLabel());
        assertEquals("2.0.6", info.getGiteeLatestVersion());
        assertEquals("https://gitee.com/super_rgh/jargus/releases/tag/v2.0.6",
                info.getGiteeReleaseUrl());
        assertEquals(1, gitee.latestCalls.get());
    }

    @Test
    void giteeColumnFailureDoesNotAffectMainColumn() {
        FakeFeed main = feed(release("v2.0.8", ReleaseFeed.Source.GITHUB));
        FakeFeed gitee = feed(null);
        gitee.latestFailure = new IllegalStateException("gitee down");

        VersionInfo info = service(main, gitee, neverCalledComparison(),
                new FakeDismissal(), "2.0.7").snapshot();

        assertEquals(VersionInfo.State.UPDATE_AVAILABLE, info.getState());
        assertEquals("GitHub", info.getLatestSourceLabel());
        assertNull(info.getGiteeLatestVersion());
    }

    @Test
    void giteeFallbackReusesMainResultWithoutSecondRequest() {
        FakeFeed main = feed(release("v2.0.8", ReleaseFeed.Source.GITEE));
        FakeFeed gitee = feed(release("v2.0.8", ReleaseFeed.Source.GITEE));

        VersionInfo info = service(main, gitee, neverCalledComparison(),
                new FakeDismissal(), "2.0.7").snapshot();

        assertEquals("Gitee", info.getLatestSourceLabel());
        assertEquals("2.0.8", info.getGiteeLatestVersion());
        assertEquals(0, gitee.latestCalls.get(), "主栏已是 Gitee 时不重复请求");
    }

    @Test
    void firstSnapshotIsCheckingWhenExecutorDropsTask() {
        VersionService service = new VersionService(
                feed(release("v2.0.8", ReleaseFeed.Source.GITHUB)), feed(null),
                null, null, "2.0.7", true, task -> { /* 不执行 */ });

        assertEquals(VersionInfo.State.CHECKING, service.snapshot().getState());
    }

    @Test
    void releaseNotesDropJavascriptUrls() {
        FakeFeed main = feed(release("v2.0.7", ReleaseFeed.Source.GITHUB));
        main.byTag = new ReleaseFeed.Release("v9.9.9", "https://github.com/vfaner/jargus", null,
                "[点我](javascript:alert(1))", List.of(), ReleaseFeed.Source.GITHUB);

        VersionInfo info = service(main, feed(null), neverCalledComparison(),
                new FakeDismissal(), "9.9.9").snapshot();

        assertNotNull(info.getCurrentNotesHtml());
        assertFalse(info.getCurrentNotesHtml().contains("javascript:"),
                "sanitizeUrls 必须剥掉危险协议: " + info.getCurrentNotesHtml());
    }

    @Test
    void compareVersionsHandlesLargeNumericSegments() {
        assertTrue(VersionService.compareVersions("1.2.10", "1.2.9") > 0);
        assertTrue(VersionService.compareVersions("v2.0.7", "2.0.7") == 0);
        assertTrue(VersionService.compareVersions("20261027", "1.0.0") > 0);
        assertTrue(VersionService.compareVersions("1.0", "1.0.0") == 0);
        assertTrue(VersionService.compareVersions("abc", "1.0") < 0);
    }

    @Test
    void trustedReleaseUrlOnlyAllowsHttpsGithubAndGitee() {
        assertEquals("https://github.com/a/b",
                VersionService.trustedReleaseUrl("https://github.com/a/b"));
        assertEquals("https://gitee.com/a/b",
                VersionService.trustedReleaseUrl("https://gitee.com/a/b"));
        assertNull(VersionService.trustedReleaseUrl("http://github.com/a/b"));
        assertNull(VersionService.trustedReleaseUrl("https://evil.com/a/b"));
        assertNull(VersionService.trustedReleaseUrl(null));
    }

    // ── helpers ───────────────────────────────────────────────

    private static VersionService service(FakeFeed main, FakeFeed gitee,
                                          ArtifactComparison comparison,
                                          ArtifactDismissal dismissal, String current) {
        return new VersionService(main, gitee, comparison, dismissal,
                current, true, Runnable::run);
    }

    private static FakeFeed feed(ReleaseFeed.Release release) {
        FakeFeed feed = new FakeFeed();
        feed.latest = release;
        return feed;
    }

    private static ReleaseFeed.Release release(String tag, ReleaseFeed.Source source) {
        String base = source == ReleaseFeed.Source.GITHUB
                ? "https://github.com/vfaner/jargus" : "https://gitee.com/super_rgh/jargus";
        return new ReleaseFeed.Release(tag, base + "/releases/tag/" + tag,
                Instant.parse("2026-10-20T12:00:00Z"), "",
                List.of(new ReleaseFeed.Asset("jargus.jar", JAR_URL, 100L,
                        "aaaa", Instant.parse("2026-10-21T12:00:00Z"))),
                source);
    }

    private static ArtifactComparison constantComparison(ArtifactComparison.Result result,
                                                           String key) {
        return release -> new ArtifactComparison.Comparison(result, key);
    }

    private static ArtifactComparison neverCalledComparison() {
        return release -> {
            throw new AssertionError("当前用例不应触发安装包比对");
        };
    }

    private static final class FakeFeed implements ReleaseFeed {
        ReleaseFeed.Release latest;
        ReleaseFeed.Release byTag;
        Exception latestFailure;
        final AtomicInteger latestCalls = new AtomicInteger();
        final AtomicInteger byTagCalls = new AtomicInteger();

        @Override
        public Release latest() throws Exception {
            latestCalls.incrementAndGet();
            if (latestFailure != null) {
                throw latestFailure;
            }
            if (latest == null) {
                throw new IllegalStateException("no release");
            }
            return latest;
        }

        @Override
        public Release byTag(String tag) throws Exception {
            byTagCalls.incrementAndGet();
            if (byTag != null) {
                return byTag;
            }
            return new ReleaseFeed.Release(tag, "https://github.com/vfaner/jargus", null, "",
                    List.of(), Source.GITHUB);
        }
    }

    private static final class FakeDismissal implements ArtifactDismissal {
        String key;

        @Override
        public String dismissedKey() {
            return key;
        }

        @Override
        public void dismiss(String artifactKey) {
            key = artifactKey;
        }
    }
}
