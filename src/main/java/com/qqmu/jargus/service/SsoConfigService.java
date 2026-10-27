package com.qqmu.jargus.service;

import com.qqmu.jargus.dto.SsoConfigForm;
import com.qqmu.jargus.dto.SsoConfigView;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.mapper.SsoConfigMapper;
import com.qqmu.jargus.util.CryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 企业统一认证配置服务。
 *
 * <p>单行表 {@code sso_config}，固定 id=1；无行时使用默认值。共享密钥以 AES
 * 密文落库，读取接口只返回 {@code secretConfigured}，绝不回显明文或密文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoConfigService {

    public static final String DEFAULT_USER_HEADER = "X-Auth-User";
    public static final String DEFAULT_ADMIN_HEADER = "X-Auth-Admin";
    public static final String DEFAULT_ADMIN_VALUE = "true";
    public static final String DEFAULT_SECRET_HEADER = "X-Auth-Secret";
    public static final String DEFAULT_TICKET_PARAM = "ticket";
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]+$");
    private static final Pattern TICKET_PARAM = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final int MAX_ADMIN_USERS = 200;
    private static final int MAX_ADMIN_USER_LENGTH = 64;
    private static final int MAX_ADMIN_USERS_JOINED_LENGTH = 1024;
    /** 共享密钥明文上限（按 UTF-8 字节）。AES/ECB 加密后 Base64约 1.4 倍，须小于 512 列宽。 */
    private static final int MAX_SECRET_BYTES = 256;
    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_FIELD_LENGTH = 128;

    private final SsoConfigMapper configMapper;
    private final AtomicBoolean readFallbackLogged = new AtomicBoolean(false);

    /** 无配置行时的默认值。 */
    public static SsoConfig defaults() {
        SsoConfig c = new SsoConfig();
        c.setId(SsoConfig.SINGLETON_ID);
        c.setIsEnabled(false);
        c.setAuthMode(SsoConfig.MODE_TICKET);
        c.setAdminUsers("");
        c.setLoginEntryUrl("");
        c.setLogoutRedirectUrl("");
        c.setLocalLoginEnabled(true);
        c.setUserHeader(DEFAULT_USER_HEADER);
        c.setAdminHeader(DEFAULT_ADMIN_HEADER);
        c.setAdminValue(DEFAULT_ADMIN_VALUE);
        c.setSecretHeaderName(DEFAULT_SECRET_HEADER);
        c.setSecretHeaderValue(null);
        c.setValidateUrl("");
        c.setValidateMethod("GET");
        c.setTicketParam(DEFAULT_TICKET_PARAM);
        c.setSuccessField("success");
        c.setSuccessValue("true");
        c.setUserField("username");
        c.setAdminField("isAdmin");
        c.setTimeoutMs(DEFAULT_TIMEOUT_MS);
        c.setAllowInsecureHttp(false);
        return c;
    }

    /**
     * 当前生效配置（共享密钥已解密）。表未就绪或读取失败时回退默认值：
     * 本地登录开启、SSO 关闭，保证启动早期不会被锁在门外。
     */
    public SsoConfig getRaw() {
        SsoConfig row = loadRow();
        SsoConfig config = row == null ? defaults() : normalize(row);
        config.setSecretHeaderValue(decryptStrict(row == null ? null : row.getSecretHeaderValue()));
        return config;
    }

    /** 管理页读取模型：不含密钥。 */
    public SsoConfigView getView() {
        return toView(getRaw());
    }

    /**
     * 校验表单；返回 null 表示通过，否则返回可直接展示的中文错误。
     */
    public String validate(SsoConfigForm form) {
        if (form == null) {
            return "配置不能为空";
        }
        String submittedSecret = trim(form.getSecretHeaderValue());
        if (hasNewSecret(submittedSecret)
                && submittedSecret.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
            return "共享密钥不能超过 " + MAX_SECRET_BYTES + " 字节（中文等宽字符按 3 字节计）";
        }
        SsoConfig existing = loadRow();
        String existingCipher = existing == null ? null : existing.getSecretHeaderValue();
        boolean hasExistingSecret = decryptStrict(existingCipher) != null;
        boolean hasSubmittedSecret = hasNewSecret(form.getSecretHeaderValue());
        SsoConfig candidate = buildEntity(form, existingCipher, hasExistingSecret || hasSubmittedSecret);
        return validateCandidate(candidate);
    }

    /**
     * 保存（调用方应先 validate；这里再校验一次防止绕过）。
     *
     * @throws IllegalArgumentException 校验失败
     */
    public SsoConfigView save(SsoConfigForm form) {
        String error = validate(form);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        SsoConfig existing = loadRow();
        String existingCipher = existing == null ? null : existing.getSecretHeaderValue();
        boolean hasExistingSecret = decryptStrict(existingCipher) != null;
        boolean hasSubmittedSecret = hasNewSecret(form.getSecretHeaderValue());
        SsoConfig entity = buildEntity(form, existingCipher, hasExistingSecret || hasSubmittedSecret);
        if ("****".equals(entity.getSecretHeaderValue())) {
            entity.setSecretHeaderValue(existingCipher == null ? "" : existingCipher);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            configMapper.insert(entity);
        } else {
            configMapper.updateById(entity);
        }
        return toView(getRaw());
    }

    /** 本地账号密码登录是否可用。DB 说关闭但 SSO 不可用时兜底为可用。 */
    public boolean isLocalLoginEnabled() {
        return isLocalLoginEnabled(getRaw());
    }

    /** 同上，供已持有配置对象的调用方复用，避免重复读库。 */
    public static boolean isLocalLoginEnabled(SsoConfig config) {
        if (config == null || !Boolean.FALSE.equals(config.getLocalLoginEnabled())) {
            return true;
        }
        return !isSsoUsable(config);
    }

    /** 配置是否足以完成一次 SSO 登录。 */
    public static boolean isSsoUsable(SsoConfig config) {
        if (config == null || !Boolean.TRUE.equals(config.getIsEnabled())) {
            return false;
        }
        String mode = upper(config.getAuthMode());
        if (SsoConfig.MODE_HEADER.equals(mode)) {
            if (isBlank(config.getUserHeader()) || !isHeaderName(config.getUserHeader())) {
                return false;
            }
            if (!isBlank(config.getSecretHeaderName())
                    && (isBlank(config.getSecretHeaderValue()) || !isHeaderName(config.getSecretHeaderName()))) {
                return false;
            }
            return true;
        }
        if (SsoConfig.MODE_TICKET.equals(mode)) {
            return !isBlank(config.getLoginEntryUrl())
                    && !isBlank(config.getValidateUrl())
                    && !isBlank(config.getUserField())
                    && !isBlank(config.getTicketParam())
                    && isHttpUrl(config.getLoginEntryUrl(), Boolean.TRUE.equals(config.getAllowInsecureHttp()))
                    && isHttpUrl(config.getValidateUrl(), Boolean.TRUE.equals(config.getAllowInsecureHttp()));
        }
        return false;
    }

    /**
     * 启动安全网：
     * - 破窗参数为 true 时强制恢复本地登录；
     * - 本地登录被关闭但 SSO 不可用时强制恢复，避免全员被锁。
     */
    public void applyStartupSafetyNets(boolean breakGlass) {
        SsoConfig row = loadRow();
        if (row == null || !Boolean.FALSE.equals(row.getLocalLoginEnabled())) {
            return;
        }
        boolean ssoUsable = isSsoUsable(getRaw());
        if (breakGlass || !ssoUsable) {
            SsoConfig update = new SsoConfig();
            update.setId(SsoConfig.SINGLETON_ID);
            update.setLocalLoginEnabled(true);
            update.setUpdatedAt(LocalDateTime.now());
            try {
                configMapper.updateById(update);
                if (breakGlass) {
                    log.warn("已通过 app.sso.reset-local-login=true 强制恢复本地登录");
                } else {
                    log.error("SSO 未配置可用，本地登录被关闭会导致无法登录，已自动恢复本地登录");
                }
            } catch (Exception e) {
                log.warn("恢复本地登录失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 管理页测试用：把表单解析成含明文密钥的临时配置（不落库、不返回前端）。
     * 空/掩码密钥沿用库中已配置值。
     */
    public SsoConfig resolveForTest(SsoConfigForm form) {
        SsoConfig existing = loadRow();
        String existingCipher = existing == null ? null : existing.getSecretHeaderValue();
        boolean hasExistingSecret = decryptStrict(existingCipher) != null;
        boolean hasSubmittedSecret = hasNewSecret(form.getSecretHeaderValue());
        SsoConfig candidate = buildEntity(form, existingCipher, hasExistingSecret || hasSubmittedSecret);
        String submitted = trim(form.getSecretHeaderValue());
        if (hasSubmittedSecret) {
            candidate.setSecretHeaderValue(submitted);
        } else {
            candidate.setSecretHeaderValue(decryptStrict(existingCipher));
        }
        return candidate;
    }

    /** 常量时间字符串比较（共享密钥）。 */
    public static boolean constantTimeEquals(String provided, String expected) {
        if (provided == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /** 登出回跳地址：空白或非法时返回 null。 */
    public String safeLogoutRedirect() {
        SsoConfig config = getRaw();
        String url = trim(config.getLogoutRedirectUrl());
        if (url.isEmpty()) {
            return null;
        }
        return isSafeLogoutRedirect(url, Boolean.TRUE.equals(config.getAllowInsecureHttp())) ? url : null;
    }

    /** 管理员白名单解析：trim、去空、忽略大小写比较。 */
    public static boolean isAdminUser(SsoConfig config, String username) {
        if (config == null || isBlank(username) || isBlank(config.getAdminUsers())) {
            return false;
        }
        String target = username.trim();
        for (String item : config.getAdminUsers().split(",")) {
            if (item.trim().equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }

    public static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private SsoConfig loadRow() {
        try {
            return configMapper.selectById(SsoConfig.SINGLETON_ID);
        } catch (Exception e) {
            if (readFallbackLogged.compareAndSet(false, true)) {
                log.warn("读取 sso_config 失败，暂用默认配置（后续同类错误不再重复打印）: {}", e.getMessage());
            }
            return null;
        }
    }

    private SsoConfig normalize(SsoConfig row) {
        row.setId(SsoConfig.SINGLETON_ID);
        row.setIsEnabled(Boolean.TRUE.equals(row.getIsEnabled()));
        row.setAuthMode(isBlank(row.getAuthMode()) ? SsoConfig.MODE_TICKET : upper(row.getAuthMode()));
        row.setAdminUsers(trim(row.getAdminUsers()));
        row.setLoginEntryUrl(trim(row.getLoginEntryUrl()));
        row.setLogoutRedirectUrl(trim(row.getLogoutRedirectUrl()));
        row.setLocalLoginEnabled(!Boolean.FALSE.equals(row.getLocalLoginEnabled()));
        row.setUserHeader(trim(row.getUserHeader()));
        row.setAdminHeader(trim(row.getAdminHeader()));
        row.setAdminValue(trim(row.getAdminValue()));
        row.setSecretHeaderName(trim(row.getSecretHeaderName()));
        row.setValidateUrl(trim(row.getValidateUrl()));
        row.setValidateMethod(isBlank(row.getValidateMethod()) ? "GET" : upper(row.getValidateMethod()));
        row.setTicketParam(isBlank(row.getTicketParam()) ? DEFAULT_TICKET_PARAM : trim(row.getTicketParam()));
        row.setSuccessField(trim(row.getSuccessField()));
        row.setSuccessValue(trim(row.getSuccessValue()));
        row.setUserField(trim(row.getUserField()));
        row.setAdminField(trim(row.getAdminField()));
        row.setTimeoutMs(row.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : row.getTimeoutMs());
        row.setAllowInsecureHttp(Boolean.TRUE.equals(row.getAllowInsecureHttp()));
        if (!isBlank(row.getSuccessField()) && isBlank(row.getSuccessValue())) {
            row.setSuccessValue("true");
        }
        if (!isBlank(row.getAdminHeader()) && isBlank(row.getAdminValue())) {
            row.setAdminValue(DEFAULT_ADMIN_VALUE);
        }
        return row;
    }

    private SsoConfig buildEntity(SsoConfigForm form, String existingCipher, boolean hasSecret) {
        SsoConfig c = new SsoConfig();
        c.setId(SsoConfig.SINGLETON_ID);
        c.setIsEnabled(Boolean.TRUE.equals(form.getIsEnabled()));
        c.setAuthMode(isBlank(form.getAuthMode()) ? SsoConfig.MODE_TICKET : upper(form.getAuthMode()));
        c.setAdminUsers(normalizeAdminUsers(form.getAdminUsers()));
        c.setLoginEntryUrl(trim(form.getLoginEntryUrl()));
        c.setLogoutRedirectUrl(trim(form.getLogoutRedirectUrl()));
        c.setLocalLoginEnabled(!Boolean.FALSE.equals(form.getLocalLoginEnabled()));
        c.setUserHeader(trim(form.getUserHeader()));
        c.setAdminHeader(trim(form.getAdminHeader()));
        c.setAdminValue(trim(form.getAdminValue()));
        c.setSecretHeaderName(trim(form.getSecretHeaderName()));
        c.setValidateUrl(trim(form.getValidateUrl()));
        c.setValidateMethod(isBlank(form.getValidateMethod()) ? "GET" : upper(form.getValidateMethod()));
        c.setTicketParam(isBlank(form.getTicketParam()) ? DEFAULT_TICKET_PARAM : trim(form.getTicketParam()));
        c.setSuccessField(trim(form.getSuccessField()));
        c.setSuccessValue(trim(form.getSuccessValue()));
        c.setUserField(trim(form.getUserField()));
        c.setAdminField(trim(form.getAdminField()));
        c.setTimeoutMs(form.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : form.getTimeoutMs());
        c.setAllowInsecureHttp(Boolean.TRUE.equals(form.getAllowInsecureHttp()));
        if (!isBlank(c.getSuccessField()) && isBlank(c.getSuccessValue())) {
            c.setSuccessValue("true");
        }
        if (!isBlank(c.getAdminHeader()) && isBlank(c.getAdminValue())) {
            c.setAdminValue(DEFAULT_ADMIN_VALUE);
        }

        String submitted = trim(form.getSecretHeaderValue());
        String cipher;
        if (hasNewSecret(submitted)) {
            cipher = CryptoUtil.encrypt(submitted);
        } else {
            cipher = existingCipher;
        }
        // 校验阶段用占位符表示“已有密钥”，保存时写入真实密文
        c.setSecretHeaderValue(hasSecret ? (hasNewSecret(submitted) ? cipher : "****") : "");
        return c;
    }

    private String validateCandidate(SsoConfig c) {
        String mode = c.getAuthMode();
        if (!SsoConfig.MODE_TICKET.equals(mode) && !SsoConfig.MODE_HEADER.equals(mode)) {
            return "认证模式只能是票据（TICKET）或网关头（HEADER）";
        }
        String method = c.getValidateMethod();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            return "票据校验方式只能是 GET 或 POST";
        }
        if (c.getTimeoutMs() == null || c.getTimeoutMs() < 1000 || c.getTimeoutMs() > 30000) {
            return "票据校验超时需在 1000–30000 毫秒之间";
        }
        if (exceeds(c.getLoginEntryUrl(), MAX_URL_LENGTH)) {
            return "企业登录中心地址不能超过 " + MAX_URL_LENGTH + " 个字符";
        }
        if (exceeds(c.getValidateUrl(), MAX_URL_LENGTH)) {
            return "票据校验地址不能超过 " + MAX_URL_LENGTH + " 个字符";
        }
        if (exceeds(c.getLogoutRedirectUrl(), MAX_URL_LENGTH)) {
            return "登出回跳地址不能超过 " + MAX_URL_LENGTH + " 个字符";
        }
        if (exceeds(c.getAdminValue(), MAX_FIELD_LENGTH)
                || exceeds(c.getSuccessField(), MAX_FIELD_LENGTH)
                || exceeds(c.getSuccessValue(), MAX_FIELD_LENGTH)
                || exceeds(c.getUserField(), MAX_FIELD_LENGTH)
                || exceeds(c.getAdminField(), MAX_FIELD_LENGTH)) {
            return "字段路径、匹配值等配置项不能超过 " + MAX_FIELD_LENGTH + " 个字符";
        }
        String adminUsersError = validateAdminUsers(c.getAdminUsers());
        if (adminUsersError != null) {
            return adminUsersError;
        }
        boolean allowInsecure = Boolean.TRUE.equals(c.getAllowInsecureHttp());

        // 票据相关地址只在票据模式下校验：切换模式后隐藏区块里的旧值不应阻断保存
        if (SsoConfig.MODE_TICKET.equals(mode)) {
            if (!isBlank(c.getLoginEntryUrl()) && !isHttpUrl(c.getLoginEntryUrl(), allowInsecure)) {
                return "企业登录中心地址必须是 http(s) 地址" + insecureHint(allowInsecure);
            }
            if (!isBlank(c.getValidateUrl()) && !isHttpUrl(c.getValidateUrl(), allowInsecure)) {
                return "票据校验地址必须是 http(s) 地址" + insecureHint(allowInsecure);
            }
            if (!isBlank(c.getTicketParam()) && !TICKET_PARAM.matcher(c.getTicketParam()).matches()) {
                return "票据参数名只能包含字母、数字、下划线、点和短横线";
            }
        }
        if (!isBlank(c.getLogoutRedirectUrl())
                && !isSafeLogoutRedirect(c.getLogoutRedirectUrl(), allowInsecure)) {
            return "登出回跳地址必须是 http(s) 地址或以单个 / 开头的站内路径";
        }
        if (!isBlank(c.getUserHeader()) && !isHeaderName(c.getUserHeader())) {
            return "用户名头名称不合法";
        }
        if (!isBlank(c.getAdminHeader()) && !isHeaderName(c.getAdminHeader())) {
            return "管理员标记头名称不合法";
        }
        if (!isBlank(c.getSecretHeaderName()) && !isHeaderName(c.getSecretHeaderName())) {
            return "共享密钥头名称不合法";
        }

        if (Boolean.TRUE.equals(c.getIsEnabled())) {
            if (SsoConfig.MODE_TICKET.equals(mode)) {
                if (isBlank(c.getLoginEntryUrl())) {
                    return "启用票据模式前必须填写企业登录中心地址";
                }
                if (isBlank(c.getValidateUrl())) {
                    return "启用票据模式前必须填写票据校验地址";
                }
                if (isBlank(c.getUserField())) {
                    return "启用票据模式前必须填写用户名字段路径";
                }
            } else {
                if (isBlank(c.getUserHeader())) {
                    return "启用网关头模式前必须填写用户名头";
                }
                if (!isBlank(c.getSecretHeaderName()) && isBlank(c.getSecretHeaderValue())) {
                    return "配置了共享密钥头时，必须设置共享密钥";
                }
            }
        }
        if (!Boolean.TRUE.equals(c.getLocalLoginEnabled()) && !isSsoUsable(c)) {
            return "关闭本地登录前必须先启用并配置企业统一认证";
        }
        return null;
    }

    private String validateAdminUsers(String adminUsers) {
        if (isBlank(adminUsers)) {
            return null;
        }
        String[] items = adminUsers.split(",");
        List<String> kept = new ArrayList<>();
        for (String item : items) {
            String name = item.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > MAX_ADMIN_USER_LENGTH) {
                return "管理员用户名不能超过 " + MAX_ADMIN_USER_LENGTH + " 个字符";
            }
            kept.add(name);
        }
        if (kept.size() > MAX_ADMIN_USERS) {
            return "管理员白名单最多 " + MAX_ADMIN_USERS + " 个用户名";
        }
        if (String.join(",", kept).length() > MAX_ADMIN_USERS_JOINED_LENGTH) {
            return "管理员白名单总长度不能超过 " + MAX_ADMIN_USERS_JOINED_LENGTH + " 个字符";
        }
        return null;
    }

    private String normalizeAdminUsers(String adminUsers) {
        if (isBlank(adminUsers)) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String item : adminUsers.split(",")) {
            String name = item.trim();
            if (!name.isEmpty()) {
                kept.add(name);
            }
        }
        return String.join(",", kept);
    }

    private boolean hasNewSecret(String value) {
        return !isBlank(value) && !value.contains("****");
    }

    /** 严格解密：解不开或往返不一致都视为未配置，避免把密文当明文用。 */
    private String decryptStrict(String cipher) {
        if (isBlank(cipher)) {
            return null;
        }
        try {
            String plain = CryptoUtil.decrypt(cipher);
            if (plain == null || plain.equals(cipher)) {
                return null;
            }
            return CryptoUtil.encrypt(plain).equals(cipher) ? plain : null;
        } catch (Exception e) {
            return null;
        }
    }

    private SsoConfigView toView(SsoConfig c) {
        SsoConfigView v = new SsoConfigView();
        v.setEnabled(Boolean.TRUE.equals(c.getIsEnabled()));
        v.setAuthMode(c.getAuthMode());
        v.setAdminUsers(c.getAdminUsers());
        v.setLoginEntryUrl(c.getLoginEntryUrl());
        v.setLogoutRedirectUrl(c.getLogoutRedirectUrl());
        v.setLocalLoginEnabled(!Boolean.FALSE.equals(c.getLocalLoginEnabled()));
        v.setUserHeader(c.getUserHeader());
        v.setAdminHeader(c.getAdminHeader());
        v.setAdminValue(c.getAdminValue());
        v.setSecretHeaderName(c.getSecretHeaderName());
        v.setSecretConfigured(!isBlank(c.getSecretHeaderValue()));
        v.setValidateUrl(c.getValidateUrl());
        v.setValidateMethod(c.getValidateMethod());
        v.setTicketParam(c.getTicketParam());
        v.setSuccessField(c.getSuccessField());
        v.setSuccessValue(c.getSuccessValue());
        v.setUserField(c.getUserField());
        v.setAdminField(c.getAdminField());
        v.setTimeoutMs(c.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : c.getTimeoutMs());
        v.setAllowInsecureHttp(Boolean.TRUE.equals(c.getAllowInsecureHttp()));
        v.setUpdatedAt(c.getUpdatedAt());
        return v;
    }

    private static boolean isHeaderName(String name) {
        if (name == null || name.length() > 128 || !HEADER_NAME.matcher(name).matches()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return !List.of("host", "content-length", "transfer-encoding", "connection",
                "cookie", "authorization", "set-cookie").contains(lower);
    }

    private static boolean isHttpUrl(String value, boolean allowInsecure) {
        if (isBlank(value)) {
            return false;
        }
        String url = value.trim();
        if (containsControl(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            if ("http".equalsIgnoreCase(scheme) && !allowInsecure) {
                return false;
            }
            return uri.getHost() != null && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSafeLogoutRedirect(String value, boolean allowInsecure) {
        if (isBlank(value) || containsControl(value)) {
            return false;
        }
        String url = value.trim();
        if (url.startsWith("/")) {
            return !url.startsWith("//") && !url.startsWith("/\\") && !url.contains("\\");
        }
        return isHttpUrl(url, allowInsecure);
    }

    private static boolean exceeds(String value, int maxLength) {
        return value != null && value.length() > maxLength;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static String insecureHint(boolean allowInsecure) {
        return allowInsecure ? "" : "（http 需勾选“允许明文 HTTP”）";
    }
}
