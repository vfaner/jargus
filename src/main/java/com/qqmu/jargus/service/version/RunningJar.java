package com.qqmu.jargus.service.version;

import java.io.File;
import java.nio.file.Path;

import org.springframework.boot.system.ApplicationHome;

import lombok.extern.slf4j.Slf4j;

/**
 * 定位当前进程运行的 Spring Boot JAR。
 *
 * <p>用 {@link ApplicationHome} 而不是自己解析 CodeSource：Boot 3.2 起
 * 嵌套 jar 的 URL 变成 {@code jar:nested:/app.jar/!BOOT-INF/classes/!/}，
 * 手工按 {@code jar:}/{@code !/} 拆会拿不到外层 jar。
 * IDE/Maven 测试中 source 是 classes 目录而非普通文件，返回 null，
 * 安装包比对自然降级为 UNKNOWN。
 */
@Slf4j
public final class RunningJar {

    private RunningJar() {
    }

    /** 当前运行的 JAR；无法定位（IDE/exploded）返回 null。 */
    public static Path locate() {
        try {
            File source = new ApplicationHome(RunningJar.class).getSource();
            return source != null && source.isFile() ? source.toPath() : null;
        } catch (Exception e) {
            log.debug("无法定位运行中的 JAR: {}", e.toString());
            return null;
        }
    }
}
