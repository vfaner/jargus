package com.qqmu.jargus.service.version;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 用 JDK 自带 HTTP 客户端读 GitHub REST API 的发布信息的实现。
 *
 * <p>API 根地址由仓库地址（app.github-url）推导，改仓库不需要第二个配置；
 * 非 github.com 主机直接拒绝，不猜其他厂商的 API 形态。
 */
@Component
@Slf4j
public class GithubReleaseFeed implements ReleaseFeed {

    /** https://github.com/owner/repo(.git)(/) 或 http 变体。 */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@github.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@github\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private static final Pattern SHA256_DIGEST =
            Pattern.compile("^sha256:([0-9a-fA-F]{64})$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final Duration timeout;
    private final HttpClient http;

    public GithubReleaseFeed(@Value("${app.github-url:}") String githubUrl,
                             @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
        String parsed;
        try {
            parsed = toApiBase(githubUrl);
        } catch (IllegalArgumentException e) {
            // 仓库地址配置错不能挡住启动；之后每次检查只报 UNKNOWN
            log.warn("版本比对已禁用：{}", e.getMessage());
            parsed = null;
        }
        this.apiBase = parsed;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 浏览器/SSH 仓库地址 → REST API 根地址。
     *
     * @throws IllegalArgumentException 非 github.com 主机或地址无法解析
     */
    static String toApiBase(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new IllegalArgumentException("app.github-url 未配置");
        }
        String url = githubUrl.trim();
        Matcher m = HTTPS_URL.matcher(url);
        if (!m.matches()) {
            m = SSH_URL.matcher(url);
        }
        if (!m.matches()) {
            throw new IllegalArgumentException("不支持的版本比对仓库地址: " + url);
        }
        return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2);
    }

    @Override
    public Release latest() throws Exception {
        return fetch(apiBase + "/releases/latest");
    }

    @Override
    public Release byTag(String tag) throws Exception {
        return fetch(apiBase + "/releases/tags/"
                + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20"));
    }

    private Release fetch(String url) throws Exception {
        if (apiBase == null) {
            throw new IllegalStateException("app.github-url 推导不出 GitHub API 根地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "jargus-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ReleaseFeed.apiFailure(Source.GITHUB, response.statusCode());
        }
        return parse(response.body());
    }

    /** 解析 release JSON（包级可见，单测直接喂字符串，不碰网络）。 */
    static Release parse(String body) throws Exception {
        JsonNode json = MAPPER.readTree(body);
        String tag = text(json, "tag_name");
        String htmlUrl = text(json, "html_url");
        if (tag == null || htmlUrl == null) {
            throw new IllegalStateException("GitHub API 响应缺少 tag_name/html_url");
        }
        Instant publishedAt = ReleaseFeed.parseInstant(text(json, "published_at"));
        String notes = json.path("body").asText("");

        List<Asset> assets = new ArrayList<>();
        JsonNode arr = json.path("assets");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String name = text(item, "name");
                String url = text(item, "browser_download_url");
                if (name == null || url == null) {
                    continue;
                }
                Long size = item.path("size").isNumber() ? item.path("size").asLong() : null;
                String sha256 = normalizeSha256(text(item, "digest"));
                String uploaded = text(item, "updated_at");
                if (uploaded == null) {
                    uploaded = text(item, "created_at");
                }
                assets.add(new Asset(name, url, size, sha256,
                        ReleaseFeed.parseInstant(uploaded)));
            }
        }
        return new Release(tag, htmlUrl, publishedAt, notes, assets, Source.GITHUB);
    }

    /** GitHub digest 形如 sha256:&lt;64 hex&gt;；只接受 sha256，统一小写。 */
    static String normalizeSha256(String digest) {
        if (digest == null) {
            return null;
        }
        Matcher m = SHA256_DIGEST.matcher(digest.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
