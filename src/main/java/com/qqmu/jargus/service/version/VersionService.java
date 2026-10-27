package com.qqmu.jargus.service.version;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * 知道当前运行版本，以及它与远端最新发布的关系。
 *
 * <p>发布源由 {@link ChainedReleaseFeed} 提供：GitHub 为主，失败自动切 Gitee；
 * 系统信息页的「Gitee 最新」栏仍独立展示（主栏已走 Gitee 时直接复用，避免重复请求）。
 * 远端检查绝不阻塞页面渲染：后台线程执行（启动后预热一次、过期再刷），
 * 结果缓存（成功 10 分钟、失败 1 分钟），过期条目边返回边后台刷新。
 */
@Service
@Slf4j
public class VersionService {

    private static final Duration POSITIVE_TTL = Duration.ofMinutes(10);
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(1);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final String currentVersion;
    /** 主发布源（生产为 GitHub→Gitee 链）。 */
    private final ReleaseFeed feed;
    /** Gitee 镜像栏独立数据源；主栏已走 Gitee 时复用主栏结果。 */
    private final ReleaseFeed giteeFeed;
    private final ArtifactComparison artifactComparison;
    private final ArtifactDismissal dismissal;
    private final boolean enabled;
    /** 网络调用离开请求线程；每次刷新一条新守护线程。 */
    private final Executor refreshExecutor;
    /** 保证同一时刻只有一个在途检查：页面连刷不会往 GitHub 堆请求。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();
    /** 发布说明渲染锁与远端刷新锁分离，首屏不被网络检查阻塞。 */
    private final Object notesLock = new Object();

    private volatile Cached cached;
    /** 当前版本发布说明的渲染结果缓存（含 null 情形）。 */
    private volatile String notesCache;
    private volatile boolean notesLoaded;

    @Autowired
    public VersionService(ChainedReleaseFeed feed, GiteeReleaseFeed giteeFeed,
                          ArtifactComparison artifactComparison, ArtifactDismissal dismissal,
                          @Value("${app.version:}") String currentVersion,
                          @Value("${app.update-check.enabled:true}") boolean enabled) {
        this(feed, giteeFeed, artifactComparison, dismissal,
                StringUtils.hasText(currentVersion) ? currentVersion.trim() : null,
                enabled, daemonExecutor());
    }

    /** 测试缝：注入假 feed 与同步执行器（如 Runnable::run），包内可见。 */
    VersionService(ReleaseFeed feed, ReleaseFeed giteeFeed,
                   ArtifactComparison artifactComparison, ArtifactDismissal dismissal,
                   String currentVersion, boolean enabled, Executor refreshExecutor) {
        this.feed = feed;
        this.giteeFeed = giteeFeed;
        this.artifactComparison = artifactComparison;
        this.dismissal = dismissal;
        this.currentVersion = currentVersion;
        this.enabled = enabled;
        this.refreshExecutor = refreshExecutor;
    }

    /** 测试缝：只关心版本状态时省略安装包比对/忽略。 */
    VersionService(ReleaseFeed feed, ReleaseFeed giteeFeed,
                   String currentVersion, boolean enabled, Executor refreshExecutor) {
        this(feed, giteeFeed, null, null, currentVersion, enabled, refreshExecutor);
    }

    /** 测试缝：同步执行 + 安装包比对/忽略。 */
    VersionService(ReleaseFeed feed, ReleaseFeed giteeFeed,
                   ArtifactComparison artifactComparison, ArtifactDismissal dismissal,
                   String currentVersion, boolean enabled) {
        this(feed, giteeFeed, artifactComparison, dismissal,
                currentVersion, enabled, Runnable::run);
    }

    /** 每次检查一条短命守护线程，空闲时不在退出阶段拴住线程池。 */
    private static Executor daemonExecutor() {
        return task -> {
            Thread thread = new Thread(task, "jargus-version-check");
            thread.setDaemon(true);
            thread.start();
        };
    }

    /** 启动完成后尽快预热缓存，离开启动主路径；失败保持 UNKNOWN。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            requestRefresh();
        }
    }

    public VersionInfo snapshot() {
        if (!enabled) {
            return VersionInfo.builder()
                    .currentVersion(currentVersion)
                    .state(VersionInfo.State.DISABLED)
                    .currentNotesHtml(currentNotes())
                    .build();
        }

        Cached c = cached;
        if (c == null || !c.validUntil().isAfter(Instant.now())) {
            // 启动预热还在途（c == null → 渲染 CHECKING），或条目过期：
            // 先返回旧值，后台检查替换
            requestRefresh();
            c = cached;
        }

        VersionInfo.VersionInfoBuilder builder = VersionInfo.builder()
                .currentVersion(currentVersion)
                .currentNotesHtml(currentNotes());
        if (c == null) {
            return builder.state(VersionInfo.State.CHECKING).build();
        }
        return builder
                .state(c.state())
                .latestTag(c.latestTag())
                .latestVersion(c.latestVersion())
                .releaseUrl(c.releaseUrl())
                .latestPublishedDate(c.latestPublishedDate())
                .latestArtifactDate(c.latestArtifactDate())
                .latestSource(c.latestSource())
                .giteeLatestVersion(c.giteeLatestVersion())
                .giteeReleaseUrl(c.giteeReleaseUrl())
                .giteeLatestPublishedDate(c.giteeLatestPublishedDate())
                .build();
    }

    /**
     * 立即同步检查一次（导航圆点/手动检查用），不写回后台缓存。
     *
     * @return 离线或关闭时 {@code checked=false}；同版本重发时 {@code artifactUpdated=true}
     */
    public UpdateCheck checkNow() {
        if (!enabled) {
            return new UpdateCheck(false, false, false,
                    currentVersion, null, null, null);
        }
        try {
            ReleaseFeed.Release latest = feed.latest();
            String latestVersion = stripV(latest.tag());
            int cmp = currentVersion == null ? 0 : compareVersions(latestVersion, currentVersion);
            boolean update = currentVersion != null && cmp > 0;
            boolean artifactUpdated = false;
            if (currentVersion != null && cmp == 0) {
                ArtifactComparison.Comparison comparison = compareArtifact(latest);
                String key = comparison.remoteIdentity();
                artifactUpdated = comparison.result() == ArtifactComparison.Result.DIFFERENT
                        && StringUtils.hasText(key)
                        && !isDismissed(key);
            }
            return new UpdateCheck(true, update || artifactUpdated, artifactUpdated,
                    currentVersion, latestVersion, latest.tag(),
                    trustedReleaseUrl(latest.htmlUrl()));
        } catch (Exception e) {
            log.debug("版本检查失败: {}", e.toString());
            return new UpdateCheck(false, false, false,
                    currentVersion, null, null, null);
        }
    }

    /**
     * 把当前「同版本安装包已重发」提示设为忽略。
     *
     * @return 没有可忽略的安装包或持久化失败时 false
     */
    public boolean dismissArtifact() {
        Cached c = cached;
        if (c == null || c.state() != VersionInfo.State.ARTIFACT_UPDATED
                || !StringUtils.hasText(c.artifactKey()) || dismissal == null) {
            return false;
        }
        try {
            dismissal.dismiss(c.artifactKey());
            cached = new Cached(VersionInfo.State.UP_TO_DATE,
                    c.latestTag(), c.latestVersion(), c.releaseUrl(), c.latestPublishedDate(),
                    null, null, c.latestSource(),
                    c.giteeLatestVersion(), c.giteeReleaseUrl(), c.giteeLatestPublishedDate(),
                    c.validUntil());
            return true;
        } catch (RuntimeException e) {
            log.warn("忽略安装包提示失败: {}", e.getMessage());
            return false;
        }
    }

    /** 除已在刷新外提交一次刷新；绝不向请求线程抛异常。 */
    private void requestRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        refreshExecutor.execute(() -> {
            try {
                refresh(Instant.now());
            } catch (Throwable t) {
                log.debug("版本检查任务失败: {}", t.toString());
            } finally {
                refreshing.set(false);
            }
        });
    }

    private synchronized Cached refresh(Instant now) {
        if (cached != null && cached.validUntil().isAfter(now)) {
            return cached; // 等待锁期间别的线程已刷完
        }

        ReleaseFeed.Release latest;
        try {
            latest = feed.latest();
        } catch (Exception e) {
            log.debug("版本检查失败: {}", e.toString());
            // 链式源已尝试 GitHub 与 Gitee；两源都失败时 Gitee 栏也没有可展示内容
            cached = new Cached(VersionInfo.State.UNKNOWN,
                    null, null, null, null, null, null, null,
                    null, null, null, now.plus(NEGATIVE_TTL));
            return cached;
        }

        String latestVersion = stripV(latest.tag());
        String latestDate = formatDate(latest.publishedAt());
        String releaseUrl = trustedReleaseUrl(latest.htmlUrl());
        VersionInfo.State state;
        String artifactDate = null;
        String artifactKey = null;

        if (currentVersion == null) {
            state = VersionInfo.State.UNKNOWN;
        } else {
            int cmp = compareVersions(latestVersion, currentVersion);
            if (cmp > 0) {
                state = VersionInfo.State.UPDATE_AVAILABLE;
            } else if (cmp == 0) {
                ArtifactComparison.Comparison comparison = compareArtifact(latest);
                String key = comparison.remoteIdentity();
                if (comparison.result() == ArtifactComparison.Result.DIFFERENT
                        && StringUtils.hasText(key) && !isDismissed(key)) {
                    state = VersionInfo.State.ARTIFACT_UPDATED;
                    artifactKey = key;
                    ReleaseFeed.Asset asset = latest.jarAsset();
                    artifactDate = asset == null ? null : formatDate(asset.updatedAt());
                } else {
                    state = VersionInfo.State.UP_TO_DATE;
                }
            } else {
                state = VersionInfo.State.UP_TO_DATE;
            }
        }

        String giteeVersion;
        String giteeUrl;
        String giteeDate;
        if (latest.source() == ReleaseFeed.Source.GITEE) {
            // 主栏已是 Gitee 兜底结果，直接复用，避免再打一次 Gitee
            giteeVersion = latestVersion;
            giteeUrl = releaseUrl;
            giteeDate = latestDate;
        } else {
            giteeVersion = null;
            giteeUrl = null;
            giteeDate = null;
            if (giteeFeed != null) {
                try {
                    ReleaseFeed.Release gitee = giteeFeed.latest();
                    giteeVersion = stripV(gitee.tag());
                    giteeUrl = trustedReleaseUrl(gitee.htmlUrl());
                    giteeDate = formatDate(gitee.publishedAt());
                } catch (Exception e) {
                    log.debug("Gitee 版本检查失败: {}", e.toString());
                }
            }
        }

        cached = new Cached(state, latest.tag(), latestVersion, releaseUrl, latestDate,
                artifactDate, artifactKey, latest.source(),
                giteeVersion, giteeUrl, giteeDate, now.plus(POSITIVE_TTL));
        return cached;
    }

    private ArtifactComparison.Comparison compareArtifact(ReleaseFeed.Release release) {
        if (artifactComparison == null) {
            return ArtifactComparison.Comparison.unknown();
        }
        try {
            return artifactComparison.compare(release);
        } catch (RuntimeException e) {
            log.debug("安装包比对失败: {}", e.toString());
            return ArtifactComparison.Comparison.unknown();
        }
    }

    private boolean isDismissed(String artifactKey) {
        if (!StringUtils.hasText(artifactKey) || dismissal == null) {
            return false;
        }
        try {
            return artifactKey.equals(dismissal.dismissedKey());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String formatDate(Instant instant) {
        return instant == null ? null : DATE.format(instant);
    }

    /**
     * 当前运行版本的发布说明（安全 HTML）。优先构建期打包进 jar 的 markdown
     *（离线主机可用），回退到对应 tag 的 release 正文（GitHub→Gitee 链）。
     */
    private String currentNotes() {
        if (!StringUtils.hasText(currentVersion)) {
            return null;
        }
        if (notesLoaded) {
            return notesCache;
        }
        synchronized (notesLock) {
            if (notesLoaded) {
                return notesCache;
            }
            String markdown = null;
            String bundled = "release-notes/RELEASE_NOTES_v" + currentVersion + ".md";
            try {
                ClassPathResource resource = new ClassPathResource(bundled);
                if (resource.exists()) {
                    markdown = StreamUtils.copyToString(resource.getInputStream(),
                            StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.debug("内置发布说明不可读: {}", bundled, e);
            }
            // 关闭检查时只读内置说明，不为一段 notes 悄悄发起网络请求
            if (!StringUtils.hasText(markdown) && enabled) {
                try {
                    markdown = feed.byTag("v" + currentVersion).body();
                } catch (Exception e) {
                    log.debug("远端无 v{} 的发布说明: {}", currentVersion, e.toString());
                }
            }
            notesCache = StringUtils.hasText(markdown)
                    ? htmlRenderer.render(markdownParser.parse(markdown))
                    : null;
            notesLoaded = true;
            return notesCache;
        }
    }

    /** 去掉一个前导 v：{@code v1.1.1} → {@code 1.1.1}。 */
    public static String stripV(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /**
     * 点分版本号逐段比较（{@code 1.2.10} &gt; {@code 1.2.9}）；
     * 缺段与非数字段按 0 计。用 BigInteger 避免日期型 tag（20261027）溢出 int。
     */
    public static int compareVersions(String a, String b) {
        String[] sa = stripV(a).split("[^0-9A-Za-z]+");
        String[] sb = stripV(b).split("[^0-9A-Za-z]+");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            BigInteger na = numericSegment(i < sa.length ? sa[i] : null);
            BigInteger nb = numericSegment(i < sb.length ? sb[i] : null);
            int cmp = na.compareTo(nb);
            if (cmp != 0) {
                return cmp > 0 ? 1 : -1;
            }
        }
        return 0;
    }

    private static BigInteger numericSegment(String part) {
        if (part == null || part.isBlank()) {
            return BigInteger.ZERO;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits.toString());
    }

    /** 只放行 https 的 github.com / gitee.com 发布页地址，避免模板输出任意链接。 */
    public static String trustedReleaseUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (!host.equals("github.com") && !host.equals("gitee.com")) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 同步检查结果（/api/version/check 直接序列化）。 */
    public record UpdateCheck(boolean checked, boolean updateAvailable,
                              boolean artifactUpdated, String currentVersion,
                              String latestVersion, String latestTag,
                              String releaseUrl) {
    }

    private record Cached(VersionInfo.State state, String latestTag, String latestVersion,
                          String releaseUrl, String latestPublishedDate,
                          String latestArtifactDate, String artifactKey,
                          ReleaseFeed.Source latestSource,
                          String giteeLatestVersion, String giteeReleaseUrl,
                          String giteeLatestPublishedDate, Instant validUntil) {
    }
}
