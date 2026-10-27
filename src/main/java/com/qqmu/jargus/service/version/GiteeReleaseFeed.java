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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 读 Gitee REST API（v5）发布信息的实现，与 {@link GithubReleaseFeed} 同纪律：
 * 可配置超时、跟随正常重定向、地址配错只降级不挡启动。
 *
 * <p>既作为「Gitee 最新版本」栏的数据源，也是 GitHub 不可达时的兜底发布源；
 * Gitee 不可达不影响 GitHub 主比对结果。
 */
@Component
@Slf4j
public class GiteeReleaseFeed implements ReleaseFeed {

    /** https://gitee.com/owner/repo(.git)(/) 或 http 变体。 */
    private static final Pattern HTTPS_URL =
            Pattern.compile("^https?://gitee\\.com/([^/]+)/([^/#?]+?)(?:\\.git)?/?$");

    /** git@gitee.com:owner/repo(.git) */
    private static final Pattern SSH_URL =
            Pattern.compile("^git@gitee\\.com:([^/]+)/([^/#?:]+?)(?:\\.git)?/?$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final String repoUrl;
    private final Duration timeout;
    private final HttpClient http;

    public GiteeReleaseFeed(@Value("${app.gitee-url:}") String giteeUrl,
                            @Value("${app.update-check.timeout-ms:5000}") long timeoutMs) {
        String parsedApi = null;
        String parsedRepo = null;
        try {
            String[] both = toApiBase(giteeUrl);
            parsedApi = both[0];
            parsedRepo = both[1];
        } catch (IllegalArgumentException e) {
            // 镜像地址未配置/配错只影响这一栏展示
            log.warn("Gitee 版本展示已禁用：{}", e.getMessage());
        }
        this.apiBase = parsedApi;
        this.repoUrl = parsedRepo;
        this.timeout = Duration.ofMillis(Math.max(500, timeoutMs));
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 浏览器/SSH 仓库地址 → [REST API 根地址, 浏览器仓库地址]。
     *
     * @throws IllegalArgumentException 非 gitee.com 主机或地址无法解析
     */
    static String[] toApiBase(String giteeUrl) {
        if (giteeUrl == null || giteeUrl.isBlank()) {
            throw new IllegalArgumentException("app.gitee-url 未配置");
        }
        String url = giteeUrl.trim();
        Matcher m = HTTPS_URL.matcher(url);
        if (!m.matches()) {
            m = SSH_URL.matcher(url);
        }
        if (!m.matches()) {
            throw new IllegalArgumentException("不支持的 Gitee 仓库地址: " + url);
        }
        String repo = "https://gitee.com/" + m.group(1) + "/" + m.group(2);
        return new String[]{"https://gitee.com/api/v5/repos/" + m.group(1) + "/" + m.group(2), repo};
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
            throw new IllegalStateException("app.gitee-url 推导不出 Gitee API 根地址");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "jargus-update-check")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw ReleaseFeed.apiFailure(Source.GITEE, response.statusCode());
        }
        return parse(response.body(), repoUrl);
    }

    /** 解析 release JSON（包级可见，单测直接喂字符串，不碰网络）。 */
    static Release parse(String body, String repoUrl) throws Exception {
        JsonNode json = MAPPER.readTree(body);
        String tag = text(json, "tag_name");
        if (tag == null) {
            throw new IllegalStateException("Gitee API 响应缺少 tag_name");
        }
        // v5 发布对象不保证带 html_url，缺省时按仓库地址拼发布页
        String htmlUrl = text(json, "html_url");
        if (htmlUrl == null) {
            htmlUrl = (repoUrl == null ? "" : repoUrl) + "/releases/tag/"
                    + URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("+", "%20");
        }
        Instant publishedAt = ReleaseFeed.parseInstant(text(json, "created_at"));

        List<Asset> assets = new ArrayList<>();
        JsonNode arr = json.path("assets");
        if (arr.isArray()) {
            for (JsonNode item : arr) {
                String name = text(item, "name");
                String url = text(item, "browser_download_url");
                if (url == null) {
                    url = text(item, "download_url");
                }
                if (name == null || url == null) {
                    continue;
                }
                Long size = item.path("size").isNumber() ? item.path("size").asLong() : null;
                String uploaded = text(item, "updated_at");
                if (uploaded == null) {
                    uploaded = text(item, "created_at");
                }
                assets.add(new Asset(name, url, size, null,
                        ReleaseFeed.parseInstant(uploaded)));
            }
        }
        return new Release(tag, htmlUrl, publishedAt, json.path("body").asText(""),
                assets, Source.GITEE);
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
