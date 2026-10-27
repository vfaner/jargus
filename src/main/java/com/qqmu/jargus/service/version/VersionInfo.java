package com.qqmu.jargus.service.version;

import lombok.Builder;
import lombok.Getter;

/**
 * 系统信息页描述「当前运行版本 vs 远端最新发布（GitHub 主、Gitee 兜底）」所需的全部字段。
 * 不可变；由 {@link VersionService#snapshot()} 基于短时效缓存每次新建。
 */
@Getter
@Builder
public class VersionInfo {

    public enum State {
        /** 当前版本即最新发布。 */
        UP_TO_DATE,
        /** 远端存在更新版本。 */
        UPDATE_AVAILABLE,
        /** 版本号相同，但远端安装包被重新上传，与当前运行 jar 不同。 */
        ARTIFACT_UPDATED,
        /** 启动后首次检查尚未完成；页面渲染不等它。 */
        CHECKING,
        /** 远端不可达或响应无法解析（离线内网、限流、仓库地址配置错）。 */
        UNKNOWN,
        /** 通过 app.update-check.enabled=false 关闭。 */
        DISABLED
    }

    /** 当前工件版本（无 v 前缀），如 1.0.0。 */
    private final String currentVersion;
    /** 最新发布版本（无 v 前缀）；未知/关闭时为 null。 */
    private final String latestVersion;
    /** 最新发布 tag，如 v1.0.1（保留原始前缀）。 */
    private final String latestTag;
    /** 最新发布页浏览器地址。 */
    private final String releaseUrl;
    /** 最新发布日期 yyyy-MM-dd；可能为 null。 */
    private final String latestPublishedDate;
    /** 同版本号安装包被重新上传的日期 yyyy-MM-dd；仅 ARTIFACT_UPDATED 时可能有值。 */
    private final String latestArtifactDate;
    /** 主栏最新发布来自哪个平台（GitHub 或 Gitee 兜底）；未知时为 null。 */
    private final ReleaseFeed.Source latestSource;
    /** Gitee 镜像最新发布版本（无 v 前缀）；未配置/不可达时为 null，仅展示不参与状态判定。 */
    private final String giteeLatestVersion;
    /** Gitee 最新发布页浏览器地址；可能为 null。 */
    private final String giteeReleaseUrl;
    /** Gitee 最新发布日期 yyyy-MM-dd；可能为 null。 */
    private final String giteeLatestPublishedDate;
    private final State state;
    /** 当前版本发布说明的安全 HTML；取不到时 null。 */
    private final String currentNotesHtml;

    /** 模板用：GitHub / Gitee；来源未知时为 null。 */
    public String getLatestSourceLabel() {
        return latestSource == null ? null : latestSource.displayName();
    }
}
