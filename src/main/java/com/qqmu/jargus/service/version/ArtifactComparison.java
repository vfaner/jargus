package com.qqmu.jargus.service.version;

/** 远端发布附件与当前运行 jar 的比对结果。 */
public interface ArtifactComparison {

    enum Result {
        /** 远端附件与本地运行 jar 相同。 */
        SAME,
        /** 同一版本号的远端附件被重新上传，与本地运行 jar 不同。 */
        DIFFERENT,
        /** 无法判断（IDE 启动、非可信 URL、缺少摘要/大小、网络失败）。 */
        UNKNOWN
    }

    /**
     * @param result         比对结果
     * @param remoteIdentity 远端附件身份（sha256:&lt;hex&gt; / size:&lt;bytes&gt;），用于「忽略」持久化
     */
    record Comparison(Result result, String remoteIdentity) {
        public static Comparison unknown() {
            return new Comparison(Result.UNKNOWN, null);
        }
    }

    Comparison compare(ReleaseFeed.Release release);
}
