package com.qqmu.jargus.service.version;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 比较远端 release 附件与当前运行 jar：
 * 优先 SHA-256（GitHub digest），其次附件大小，最后 HEAD 取 Content-Length。
 *
 * <p>只信任 github.com / gitee.com 的 https 下载地址；本地 jar 只哈希一次并缓存。
 */
@Component
@Slf4j
public class JarArtifactComparison implements ArtifactComparison {

    @FunctionalInterface
    public interface SizeProbe {
        Long contentLength(String url);
    }

    private final Supplier<Path> jarLocation;
    private final boolean enabled;
    private final SizeProbe sizeProbe;

    private volatile String localSha256;
    private volatile Long localSize;

    @Autowired
    public JarArtifactComparison(
            @Value("${app.update-check.match-artifact:true}") boolean enabled,
            @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
        this(RunningJar::locate, enabled,
                httpProbe(Duration.ofMillis(Math.max(500, timeoutMs))));
    }

    /** 测试缝：注入临时文件与假 HEAD 探测。 */
    JarArtifactComparison(Supplier<Path> jarLocation, boolean enabled, SizeProbe sizeProbe) {
        this.jarLocation = jarLocation;
        this.enabled = enabled;
        this.sizeProbe = sizeProbe;
    }

    @Override
    public Comparison compare(ReleaseFeed.Release release) {
        if (!enabled || release == null) {
            return Comparison.unknown();
        }
        ReleaseFeed.Asset asset = release.jarAsset();
        if (asset == null || !isTrustedDownloadUrl(asset.url())) {
            return Comparison.unknown();
        }
        Path jar = jarLocation.get();
        if (jar == null || !Files.isRegularFile(jar)) {
            return Comparison.unknown();
        }
        try {
            if (asset.sha256() != null && !asset.sha256().isBlank()) {
                String local = localSha256(jar);
                if (local == null) {
                    return Comparison.unknown();
                }
                String remote = asset.sha256().toLowerCase(Locale.ROOT);
                return new Comparison(local.equalsIgnoreCase(remote)
                        ? Result.SAME : Result.DIFFERENT, "sha256:" + remote);
            }
            long localBytes = localSize(jar);
            Long remoteBytes = asset.sizeBytes();
            if (remoteBytes == null) {
                remoteBytes = sizeProbe.contentLength(asset.url());
            }
            if (remoteBytes == null || remoteBytes <= 0) {
                return Comparison.unknown();
            }
            return new Comparison(localBytes == remoteBytes
                    ? Result.SAME : Result.DIFFERENT, "size:" + remoteBytes);
        } catch (Exception e) {
            log.debug("安装包比对失败: {}", e.toString());
            return Comparison.unknown();
        }
    }

    private String localSha256(Path jar) throws Exception {
        String cached = localSha256;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (localSha256 == null) {
                localSha256 = sha256(jar);
            }
            return localSha256;
        }
    }

    private long localSize(Path jar) throws Exception {
        Long cached = localSize;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (localSize == null) {
                localSize = Files.size(jar);
            }
            return localSize;
        }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean isTrustedDownloadUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("github.com") || host.equals("gitee.com");
        } catch (Exception e) {
            return false;
        }
    }

    static SizeProbe httpProbe(Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", "jargus-update-check")
                        .build();
                HttpResponse<Void> response =
                        http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() != 200) {
                    return null;
                }
                return response.headers().firstValue("Content-Length")
                        .map(Long::parseLong).orElse(null);
            } catch (Exception e) {
                return null;
            }
        };
    }
}
