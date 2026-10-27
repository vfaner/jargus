package com.qqmu.jargus.service.version;

/** 同版本号安装包重发提示的「忽略」持久化。 */
public interface ArtifactDismissal {

    /** 已忽略的安装包身份；没有记录返回 null。 */
    String dismissedKey();

    /** 忽略指定安装包身份。 */
    void dismiss(String artifactKey);
}
