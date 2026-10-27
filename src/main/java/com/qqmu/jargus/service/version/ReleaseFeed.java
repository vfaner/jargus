package com.qqmu.jargus.service.version;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

/** 发布源抽象（单测可换内存实现，不碰网络）。 */
public interface ReleaseFeed {

    /** 一键升级使用的固定安装包名。 */
    String JAR_ASSET_NAME = "jargus.jar";

    /** 历史版本的带版本号安装包名，如 jargus-2.0.3.jar。 */
    Pattern VERSIONED_JAR = Pattern.compile("(?i)^jargus-\\d[\\dA-Za-z._-]*\\.jar$");

    enum Source {
        GITHUB, GITEE;

        public String displayName() {
            return this == GITHUB ? "GitHub" : "Gitee";
        }
    }

    /** GitHub 未认证请求 403/429 即限流；单独类型便于日志区分，不影响兜底逻辑。 */
    class RateLimitedException extends Exception {
        private final Source source;
        private final int statusCode;

        RateLimitedException(Source source, int statusCode) {
            super(source.displayName() + " API 限流（HTTP " + statusCode + "）");
            this.source = source;
            this.statusCode = statusCode;
        }

        public Source source() {
            return source;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    static Exception apiFailure(Source source, int statusCode) {
        if (statusCode == 403 || statusCode == 429) {
            return new RateLimitedException(source, statusCode);
        }
        return new IllegalStateException(source.displayName() + " API 返回 " + statusCode);
    }

    /** 宽松解析 ISO 时间：先 Instant，再 OffsetDateTime（Gitee 的 +08:00）。 */
    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            // 继续尝试带偏移量的格式
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 发布附件。sha256 存 64 位小写 hex（不带前缀）；size 只信任 GitHub 源，
     * Gitee 的 size 不参与比对（见 {@link JarArtifactComparison}）。
     */
    record Asset(String name, String url, Long sizeBytes, String sha256, Instant updatedAt) {

        public Asset(String name, String url, Long sizeBytes) {
            this(name, url, sizeBytes, null, null);
        }

        public Asset(String name, String url, Long sizeBytes, String sha256) {
            this(name, url, sizeBytes, sha256, null);
        }
    }

    record Release(String tag, String htmlUrl, Instant publishedAt, String body,
                   List<Asset> assets, Source source) {

        public Release {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }

        /** 按名称大小写不敏感查找安装包。 */
        public Asset jarAsset(String jarName) {
            if (jarName == null || jarName.isBlank()) {
                return null;
            }
            for (Asset asset : assets) {
                if (asset != null && asset.name() != null
                        && asset.name().equalsIgnoreCase(jarName)) {
                    return asset;
                }
            }
            return null;
        }

        /** 优先 jargus.jar，其次 jargus-&lt;版本&gt;.jar。 */
        public Asset jarAsset() {
            Asset exact = jarAsset(JAR_ASSET_NAME);
            if (exact != null) {
                return exact;
            }
            for (Asset asset : assets) {
                if (asset != null && asset.name() != null
                        && VERSIONED_JAR.matcher(asset.name()).matches()) {
                    return asset;
                }
            }
            return null;
        }
    }

    /** 最新发布；远端无发布或不可达时抛异常。 */
    Release latest() throws Exception;

    /** 按 tag 取发布（用于回查当前版本的发布说明正文）。 */
    Release byTag(String tag) throws Exception;
}
