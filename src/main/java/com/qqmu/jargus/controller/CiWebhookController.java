package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.entity.CiScanRecord;
import com.qqmu.jargus.exception.CiWebhookUnauthorizedException;
import com.qqmu.jargus.security.PublicAccess;
import com.qqmu.jargus.service.CiWebhookAuthService;
import com.qqmu.jargus.service.CiWebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * CI Webhook 控制器
 *
 * 接收 Git 平台的 Webhook 事件，触发代码扫描。
 * 公开接口，安全性由 {@link CiWebhookAuthService} 的签名/令牌校验保证。
 */
@Slf4j
@PublicAccess
@RestController
@RequestMapping("/api/ci/webhook")
@RequiredArgsConstructor
public class CiWebhookController {

    private final CiWebhookService ciWebhookService;
    private final CiWebhookAuthService ciWebhookAuthService;
    private final ObjectMapper objectMapper;

    /**
     * 通用 JSON Webhook（GitHub/GitLab/Gitee/通用）
     *
     * @param triggerId 触发配置 ID
     * @param platform  平台: GITHUB / GITLAB / GITEE / GENERIC
     * @param rawBody   原始请求体字节（GitHub HMAC 签名必须按原始字节计算；先转 String
     *                  再按 UTF-8 取字节，在非 UTF-8 请求或非 ASCII 内容下会与签名不一致）
     */
    @PostMapping("/{triggerId}")
    public ResponseEntity<Result<CiScanRecord>> handleWebhook(
            @PathVariable Long triggerId,
            @RequestParam(defaultValue = "GENERIC") String platform,
            @RequestBody byte[] rawBody,
            HttpServletRequest request
    ) {
        try {
            // 先鉴权后解析：签名密钥不匹配直接 401
            ciWebhookAuthService.verify(triggerId, request, rawBody);

            log.info("收到 CI Webhook: triggerId={}, platform={}, contentLength={}",
                    triggerId, platform, rawBody.length);

            // 解析 JSON
            Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);

            // 特殊处理：GitHub 从 header 获取事件类型
            String githubEvent = request.getHeader("X-GitHub-Event");
            String gitlabEvent = request.getHeader("X-Gitlab-Event");
            String giteeEvent = request.getHeader("X-Gitee-Event");

            String actualPlatform = platform;
            if ("GENERIC".equalsIgnoreCase(platform)) {
                if (githubEvent != null) actualPlatform = "GITHUB";
                else if (gitlabEvent != null) actualPlatform = "GITLAB";
                else if (giteeEvent != null) actualPlatform = "GITEE";
            }

            CiScanRecord record = ciWebhookService.handleWebhook(triggerId, payload, actualPlatform);

            if (record == null) {
                return ResponseEntity.ok(Result.success(null));
            }
            return ResponseEntity.ok(Result.success(record));

        } catch (CiWebhookUnauthorizedException e) {
            log.warn("Webhook 鉴权失败: triggerId={}, err={}", triggerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(e.getMessage()));
        } catch (IOException e) {
            log.error("Webhook payload 解析失败: triggerId={}", triggerId, e);
            return ResponseEntity.badRequest().body(Result.error("Invalid JSON payload"));
        } catch (RuntimeException e) {
            log.warn("Webhook 处理失败: triggerId={}, err={}", triggerId, e.getMessage());
            return ResponseEntity.badRequest().body(Result.error(e.getMessage()));
        }
    }

    /**
     * 直接上传 ZIP 的 Webhook（通用 CI 场景）
     *
     * 鉴权：Authorization: Bearer（触发器密钥或系统访问令牌），或 X-Ci-Token 头。
     *
     * @param triggerId 触发配置 ID
     * @param file      ZIP 文件
     * @param branch    分支名（可选）
     * @param commitId  commit ID（可选）
     */
    @PostMapping("/{triggerId}/upload")
    public ResponseEntity<Result<CiScanRecord>> handleZipUpload(
            @PathVariable Long triggerId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String commitId,
            HttpServletRequest request
    ) {
        try {
            // multipart 体不做 HMAC，仅校验令牌
            ciWebhookAuthService.verify(triggerId, request, null);

            byte[] zipData = file.getBytes();
            log.info("收到 CI ZIP Webhook: triggerId={}, size={}, branch={}",
                    triggerId, zipData.length, branch);

            CiScanRecord record = ciWebhookService.handleZipUpload(
                    triggerId, zipData, branch, commitId);

            return ResponseEntity.ok(Result.success(record));
        } catch (CiWebhookUnauthorizedException e) {
            log.warn("ZIP Webhook 鉴权失败: triggerId={}, err={}", triggerId, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(e.getMessage()));
        } catch (Exception e) {
            log.error("CI ZIP Webhook 处理失败: triggerId={}", triggerId, e);
            return ResponseEntity.badRequest().body(Result.error(e.getMessage()));
        }
    }
}
