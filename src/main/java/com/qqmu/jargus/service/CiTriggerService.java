package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.CiTriggerConfig;
import com.qqmu.jargus.entity.CiScanRecord;
import com.qqmu.jargus.mapper.CiTriggerConfigMapper;
import com.qqmu.jargus.mapper.CiScanRecordMapper;
import com.qqmu.jargus.util.CryptoUtil;
import com.qqmu.jargus.util.PageUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * CI 触发配置服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CiTriggerService {

    private final CiTriggerConfigMapper triggerConfigMapper;
    private final CiScanRecordMapper scanRecordMapper;

    @Value("${app.webhook-base-url:http://localhost:8080}")
    private String webhookBaseUrl;

    private static final SecureRandom random = new SecureRandom();

    // ==================== 触发配置 CRUD ====================

    public IPage<CiTriggerConfig> list(int pageNum, int pageSize, String keyword) {
        Page<CiTriggerConfig> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<CiTriggerConfig> wrapper = new QueryWrapper<>();
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like("config_name", keyword);
        }
        wrapper.orderByDesc("created_at");
        return triggerConfigMapper.selectPage(page, wrapper);
    }

    /**
     * 管理后台读取：secretToken 解密明文（用于配置 Webhook 时复制），
     * repoToken 属于凭据，不回显（置空，编辑框留空=不修改）。
     */
    public CiTriggerConfig getById(Long id) {
        CiTriggerConfig config = triggerConfigMapper.selectById(id);
        if (config != null) {
            config.setSecretToken(decryptStored(config.getSecretToken()));
            // 不回显令牌明文；用空串表示"已配置"（未配置则为 null，JSON 直接省略）
            config.setRepoToken(config.getRepoToken() != null ? "" : null);
        }
        return config;
    }

    public CiTriggerConfig create(CiTriggerConfig config) {
        normalizePlatformUrl(config);
        // 生成 secret token（明文仅本次返回给页面展示一次，库存密文）
        String secret = generateSecret();
        // 私有库令牌加密落库
        config.setRepoToken(normalizeEncryptedSecret(config.getRepoToken()));
        if (config.getRepoUsername() != null && config.getRepoUsername().isBlank()) {
            config.setRepoUsername(null);
        }
        config.setWebhookUrl(webhookBaseUrl + "/api/ci/webhook/" + config.getId());
        config.setSecretToken(CryptoUtil.encrypt(secret));
        config.setIsEnabled(config.getIsEnabled() != null ? config.getIsEnabled() : true);
        config.setSkipUnitTest(config.getSkipUnitTest() != null ? config.getSkipUnitTest() : true);
        config.setIncludeTestCode(config.getIncludeTestCode() != null ? config.getIncludeTestCode() : false);
        config.setEnableAiReview(config.getEnableAiReview() != null ? config.getEnableAiReview() : true);
        config.setAutoComment(config.getAutoComment() != null ? config.getAutoComment() : false);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        triggerConfigMapper.insert(config);
        // 回填 webhook URL
        config.setWebhookUrl(webhookBaseUrl + "/api/ci/webhook/" + config.getId());
        triggerConfigMapper.updateById(config);
        // 明文密钥只返回这一次
        config.setSecretToken(secret);
        return config;
    }

    public boolean update(Long id, CiTriggerConfig config) {
        config.setId(id);
        normalizePlatformUrl(config);
        config.setSecretToken(null);  // 不允许通过编辑修改 secret（重置走 reset-secret）
        config.setWebhookUrl(null);
        // 仓库令牌：留空(null)=不修改；非空=加密后覆盖
        boolean tokenProvided = config.getRepoToken() != null && !config.getRepoToken().isBlank();
        if (tokenProvided) {
            config.setRepoToken(CryptoUtil.encrypt(config.getRepoToken().trim()));
        }
        config.setUpdatedAt(LocalDateTime.now());
        boolean ok = triggerConfigMapper.updateById(config) > 0;
        // updateById 默认忽略 null 字段：以下字段需要显式置空
        var clearWrapper = new UpdateWrapper<CiTriggerConfig>()
                .eq("id", id);
        boolean needClear = false;
        if (config.getPlatformUrl() == null) {
            clearWrapper.set("platform_url", null);
            needClear = true;
        }
        // 用户名传空串=清除凭据；本次没有提交新令牌时把已存令牌一并清掉
        if (config.getRepoUsername() != null && config.getRepoUsername().isBlank()) {
            clearWrapper.set("repo_username", null);
            if (!tokenProvided) {
                clearWrapper.set("repo_token", null);
            }
            needClear = true;
        }
        // 关闭发信或清空收件人时，显式清掉旧 id 串（避免下次开启时带出陈旧快照）
        if (config.getNotifyRecipientIds() == null) {
            clearWrapper.set("notify_recipient_ids", null);
            needClear = true;
        }
        if (ok && needClear) {
            triggerConfigMapper.update(null, clearWrapper);
        }
        return ok;
    }

    /** 空值归一为 null，非空加密 */
    private String normalizeEncryptedSecret(String plain) {
        if (plain == null || plain.isBlank()) return null;
        return CryptoUtil.encrypt(plain.trim());
    }

    /**
     * 解密库存的敏感值（Webhook 密钥、仓库令牌）；
     * 兼容历史明文数据（CryptoUtil.decrypt 失败时原样返回）。
     */
    public static String decryptStored(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        return CryptoUtil.decrypt(stored);
    }

    /**
     * 规范化平台地址：去空白/末尾斜杠；为空时按平台填官方云默认地址；
     * Generic 没有固定平台，置空。
     */
    private void normalizePlatformUrl(CiTriggerConfig config) {
        String url = config.getPlatformUrl();
        if (url != null) {
            url = url.trim();
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        }
        if (url == null || url.isEmpty()) {
            url = defaultPlatformUrl(config.getPlatform());
        }
        config.setPlatformUrl(url);
    }

    /** 各平台官方云默认地址；企业自建时由用户在表单里改成内网地址 */
    public static String defaultPlatformUrl(String platform) {
        if (platform == null) return null;
        return switch (platform.toUpperCase()) {
            case "GITHUB" -> "https://github.com";
            case "GITLAB" -> "https://gitlab.com";
            case "GITEE" -> "https://gitee.com";
            default -> null; // GENERIC
        };
    }

    public boolean delete(Long id) {
        return triggerConfigMapper.deleteById(id) > 0;
    }

    public boolean toggle(Long id) {
        CiTriggerConfig config = triggerConfigMapper.selectById(id);
        if (config == null) return false;
        config.setIsEnabled(!Boolean.TRUE.equals(config.getIsEnabled()));
        config.setUpdatedAt(LocalDateTime.now());
        return triggerConfigMapper.updateById(config) > 0;
    }

    /**
     * 重置 Webhook Secret
     */
    public String resetSecret(Long id) {
        CiTriggerConfig config = triggerConfigMapper.selectById(id);
        if (config == null) return null;
        String newSecret = generateSecret();
        config.setSecretToken(CryptoUtil.encrypt(newSecret));
        config.setUpdatedAt(LocalDateTime.now());
        triggerConfigMapper.updateById(config);
        return newSecret;
    }

    // ==================== CI 扫描记录 ====================

    public IPage<CiScanRecord> listRecords(int pageNum, int pageSize, Long triggerConfigId) {
        Page<CiScanRecord> page = new Page<>(PageUtils.pageNum(pageNum), PageUtils.pageSize(pageSize));
        QueryWrapper<CiScanRecord> wrapper = new QueryWrapper<>();
        if (triggerConfigId != null) {
            wrapper.eq("trigger_config_id", triggerConfigId);
        }
        wrapper.orderByDesc("created_at");
        return scanRecordMapper.selectPage(page, wrapper);
    }

    public CiScanRecord createRecord(CiScanRecord record) {
        record.setCreatedAt(LocalDateTime.now());
        if (record.getStatus() == null) record.setStatus("PENDING");
        scanRecordMapper.insert(record);
        return record;
    }

    /**
     * 关联扫描任务 ID（创建任务后回写，结果页链接与状态回写都依赖它）。
     */
    public void attachTask(Long recordId, Long taskId) {
        if (recordId == null || taskId == null) return;
        CiScanRecord upd = new CiScanRecord();
        upd.setTaskId(taskId);
        scanRecordMapper.update(upd, new QueryWrapper<CiScanRecord>().eq("id", recordId));
    }

    /** 删除单条 CI 扫描记录（记录页每行删除按钮，清理触发失败等无结果记录） */
    public void deleteRecord(Long id) {
        scanRecordMapper.deleteById(id);
    }

    /**
     * 扫描任务结束后按 taskId 回写 CI 记录状态（普通扫描任务匹配不到行，无副作用）。
     */
    public void syncRecordByTaskId(Long taskId, String status) {
        if (taskId == null || status == null) return;
        CiScanRecord upd = new CiScanRecord();
        upd.setStatus(status);
        scanRecordMapper.update(upd, new QueryWrapper<CiScanRecord>()
                .eq("task_id", taskId).ne("status", "SUCCESS"));
    }

    public boolean updateRecordStatus(Long recordId, String status, String resultUrl) {
        CiScanRecord record = new CiScanRecord();
        record.setId(recordId);
        record.setStatus(status);
        if (resultUrl != null) record.setResultUrl(resultUrl);
        return scanRecordMapper.updateById(record) > 0;
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
