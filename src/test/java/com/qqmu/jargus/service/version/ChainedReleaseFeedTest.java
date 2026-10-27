package com.qqmu.jargus.service.version;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub 优先、Gitee 兜底的链式发布源。 */
class ChainedReleaseFeedTest {

    @Test
    void githubSuccessDoesNotTouchGitee() throws Exception {
        RecordingFeed github = new RecordingFeed(release("v2.0.7", ReleaseFeed.Source.GITHUB));
        RecordingFeed gitee = new RecordingFeed(release("v2.0.6", ReleaseFeed.Source.GITEE));

        ReleaseFeed.Release result = new ChainedReleaseFeed(github, gitee).latest();

        assertSame(github.latestRelease, result);
        assertEquals(1, github.latestCalls.get());
        assertEquals(0, gitee.latestCalls.get());
    }

    @Test
    void fallsBackToGiteeWhenGithubFails() throws Exception {
        RecordingFeed github = new RecordingFeed(null);
        github.latestFailure = new IllegalStateException("offline");
        RecordingFeed gitee = new RecordingFeed(release("v2.0.7", ReleaseFeed.Source.GITEE));

        ReleaseFeed.Release result = new ChainedReleaseFeed(github, gitee).latest();

        assertEquals(ReleaseFeed.Source.GITEE, result.source());
        assertEquals(1, gitee.latestCalls.get());
    }

    @Test
    void bothFailuresAreAggregated() {
        RecordingFeed github = new RecordingFeed(null);
        github.latestFailure = new IllegalStateException("github down");
        RecordingFeed gitee = new RecordingFeed(null);
        gitee.latestFailure = new IllegalStateException("gitee down");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new ChainedReleaseFeed(github, gitee).latest());

        assertTrue(thrown.getMessage().contains("均不可用"));
        assertTrue(thrown.getCause() instanceof IllegalStateException);
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test
    void byTagAlsoFallsBack() throws Exception {
        RecordingFeed github = new RecordingFeed(null);
        github.byTagFailure = new IllegalStateException("missing");
        RecordingFeed gitee = new RecordingFeed(release("v2.0.7", ReleaseFeed.Source.GITEE));

        ReleaseFeed.Release result = new ChainedReleaseFeed(github, gitee).byTag("v2.0.7");

        assertEquals("v2.0.7", result.tag());
        assertEquals(1, github.byTagCalls.get());
        assertEquals(1, gitee.byTagCalls.get());
    }

    private static ReleaseFeed.Release release(String tag, ReleaseFeed.Source source) {
        String base = source == ReleaseFeed.Source.GITHUB
                ? "https://github.com/vfaner/jargus" : "https://gitee.com/super_rgh/jargus";
        return new ReleaseFeed.Release(tag, base + "/releases/tag/" + tag, null, "", null, source);
    }

    private static final class RecordingFeed implements ReleaseFeed {
        final ReleaseFeed.Release latestRelease;
        final AtomicInteger latestCalls = new AtomicInteger();
        final AtomicInteger byTagCalls = new AtomicInteger();
        Exception latestFailure;
        Exception byTagFailure;

        RecordingFeed(ReleaseFeed.Release latestRelease) {
            this.latestRelease = latestRelease;
        }

        @Override
        public Release latest() throws Exception {
            latestCalls.incrementAndGet();
            if (latestFailure != null) {
                throw latestFailure;
            }
            return latestRelease;
        }

        @Override
        public Release byTag(String tag) throws Exception {
            byTagCalls.incrementAndGet();
            if (byTagFailure != null) {
                throw byTagFailure;
            }
            return release(tag, ReleaseFeed.Source.GITHUB);
        }
    }
}
