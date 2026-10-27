package com.qqmu.jargus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.exception.SsoLoginException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLException;

/**
 * 企业票据校验客户端。
 *
 * <p>只接受 2xx + JSON 对象；不跟随重定向，响应体最大 64KB，超时取配置值。
 * 票据、共享密钥和响应原文都不写日志。
 */
@Slf4j
@Service
public class SsoTicketClient {

    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final String TEST_TICKET = "__connectivity_test__";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 复用同一个客户端：每次登录新建 HttpClient 会留驻选择器线程。
     * 连接超时取上限 5 秒，实际仍由请求级 {@code timeout}（含连接阶段）约束。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 完整校验票据，返回解析后的 JSON 对象。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(SsoConfig config, String ticket) {
        HttpResponse<InputStream> response = send(config, ticket);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("票据校验接口返回 HTTP {}", response.statusCode());
            // 关闭响应体，否则连接不会回收
            drain(response);
            throw new SsoLoginException("票据校验失败（HTTP " + response.statusCode() + "）");
        }
        String body = readCapped(response);
        try {
            Object parsed = OBJECT_MAPPER.readValue(body, Object.class);
            if (!(parsed instanceof Map<?, ?> map)) {
                throw new SsoLoginException("票据校验响应格式不正确");
            }
            return (Map<String, Object>) map;
        } catch (SsoLoginException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoLoginException("票据校验响应格式不正确");
        }
    }

    /** 连通性探测：用固定占位票据请求一次，只判断地址/网络/状态码。 */
    public ProbeResult probe(SsoConfig config) {
        try {
            HttpResponse<InputStream> response = send(config, TEST_TICKET);
            drain(response);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new ProbeResult(true, "接口连通正常（HTTP " + status + "）", status);
            }
            if (status == 401 || status == 403) {
                return new ProbeResult(true, "接口可达（HTTP " + status + "），占位票据被拒绝属正常", status);
            }
            if (status == 404) {
                return new ProbeResult(false, "接口返回 404，请检查票据校验 URL", status);
            }
            if (status >= 300 && status < 400) {
                return new ProbeResult(false, "接口返回重定向（HTTP " + status
                        + "），请确认填写的是校验 API 而不是网页地址", status);
            }
            if (status >= 400 && status < 500) {
                return new ProbeResult(true, "接口可达（HTTP " + status + "），建议用真实票据再验证一次", status);
            }
            return new ProbeResult(false, "对端服务异常（HTTP " + status + "）", status);
        } catch (SsoLoginException e) {
            return new ProbeResult(false, e.getMessage(), 0);
        }
    }

    private HttpResponse<InputStream> send(SsoConfig config, String ticket) {
        String url = config.getValidateUrl();
        if (url == null || url.isBlank()) {
            throw new SsoLoginException("票据校验地址未配置");
        }
        int timeoutMs = config.getTimeoutMs() == null ? SsoConfigService.DEFAULT_TIMEOUT_MS
                : Math.max(1000, Math.min(30000, config.getTimeoutMs()));
        String method = SsoConfigService.upper(config.getValidateMethod());
        String param = config.getTicketParam() == null || config.getTicketParam().isBlank()
                ? SsoConfigService.DEFAULT_TICKET_PARAM : config.getTicketParam();
        String encoded = URLEncoder.encode(ticket == null ? "" : ticket, StandardCharsets.UTF_8);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(buildUrl(url, method, param, encoded)))
                    .timeout(Duration.ofMillis(timeoutMs));
            if ("POST".equals(method)) {
                builder.header("Content-Type", "application/x-www-form-urlencoded");
                builder.POST(HttpRequest.BodyPublishers.ofString(param + "=" + encoded));
            } else {
                builder.GET();
            }
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new SsoLoginException("票据校验请求超时");
        } catch (UnknownHostException e) {
            throw new SsoLoginException("票据校验地址域名无法解析");
        } catch (ConnectException e) {
            throw new SsoLoginException("票据校验地址连接被拒绝");
        } catch (SSLException e) {
            throw new SsoLoginException("票据校验地址 HTTPS 握手失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SsoLoginException("票据校验请求被中断");
        } catch (IllegalArgumentException e) {
            throw new SsoLoginException("票据校验地址不合法");
        } catch (Exception e) {
            log.warn("票据校验请求失败: {}", e.getClass().getSimpleName());
            throw new SsoLoginException("票据校验请求失败");
        }
    }

    private String buildUrl(String rawUrl, String method, String param, String encodedTicket) {
        String url = rawUrl.trim();
        int hash = url.indexOf('#');
        if (hash >= 0) {
            url = url.substring(0, hash);
        }
        if ("POST".equals(method)) {
            return url;
        }
        String separator;
        if (!url.contains("?")) {
            separator = "?";
        } else if (url.endsWith("?") || url.endsWith("&")) {
            separator = "";
        } else {
            separator = "&";
        }
        return url + separator + param + "=" + encodedTicket;
    }

    private String readCapped(HttpResponse<InputStream> response) {
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared > MAX_RESPONSE_BYTES) {
            drain(response);
            throw new SsoLoginException("票据校验响应过大");
        }
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new SsoLoginException("票据校验响应过大");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (SsoLoginException e) {
            throw e;
        } catch (Exception e) {
            throw new SsoLoginException("读取票据校验响应失败");
        }
    }

    private void drain(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    return;
                }
            }
        } catch (Exception e) {
            // 探测场景忽略关闭异常
        }
    }

    public record ProbeResult(boolean reachable, String message, int status) {
    }
}
