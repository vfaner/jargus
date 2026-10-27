package com.qqmu.jargus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.exception.SsoLoginException;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JsonFieldUtils;
import com.qqmu.jargus.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业统一认证登录：票据/网关头身份解析、角色判定、用户落库与 JWT 签发。
 *
 * <p>角色规则：命中 {@code adminUsers} 白名单或认证标记为真 → ADMIN，其余 VIEWER。
 * 本地账号（source=LOCAL）永远不会被 SSO 接管；被禁用的账号也不会被 IdP 重新启用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoAuthService {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final String ROLE_VIEWER = "VIEWER";
    private static final String SOURCE_LOCAL = "LOCAL";
    private static final String SOURCE_REMOTE = "REMOTE";
    private static final String SOURCE_SSO = "SSO";

    private final SsoTicketClient ticketClient;
    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;

    /** 票据回调登录。 */
    public SsoLoginResult loginByTicket(SsoConfig config, String ticket) {
        TestIdentity identity = inspectTicket(config, ticket);
        return issue(config, identity.username(), identity.adminMarker());
    }

    /** 校验票据并解析身份，但**不落库**（管理页测试使用）。 */
    public TestIdentity inspectTicket(SsoConfig config, String ticket) {
        Map<String, Object> response = ticketClient.validate(config, ticket);

        if (!SsoConfigService.isBlank(config.getSuccessField())
                && !JsonFieldUtils.matches(
                RemoteAuthConfigService.extractField(response, config.getSuccessField()),
                config.getSuccessValue())) {
            throw new SsoLoginException("票据校验未通过");
        }

        String username = sanitizeUsername(JsonFieldUtils.asText(
                RemoteAuthConfigService.extractField(response, config.getUserField())));
        boolean adminMarker = !SsoConfigService.isBlank(config.getAdminField())
                && JsonFieldUtils.truthy(
                RemoteAuthConfigService.extractField(response, config.getAdminField()));
        return new TestIdentity(username, resolveRole(config, username, adminMarker), adminMarker);
    }

    /** 受信网关头登录。 */
    public SsoLoginResult authenticateHeader(SsoConfig config, String rawUsername, boolean adminMarker) {
        String username = sanitizeUsername(rawUsername);
        return issue(config, username, adminMarker);
    }

    private SsoLoginResult issue(SsoConfig config, String username, boolean adminMarker) {
        String role = resolveRole(config, username, adminMarker);
        SysUser user = provision(username, role);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new SsoLoginResult(user, token);
    }

    public String resolveRole(SsoConfig config, String username, boolean adminMarker) {
        if (adminMarker || SsoConfigService.isAdminUser(config, username)) {
            return ROLE_ADMIN;
        }
        return ROLE_VIEWER;
    }

    /** 用户名：trim、1–64 字符、不允许空白/控制字符。 */
    public static String sanitizeUsername(String raw) {
        if (raw == null) {
            throw new SsoLoginException("统一认证未返回用户名");
        }
        String username = raw.trim();
        if (username.isEmpty() || username.length() > 64) {
            throw new SsoLoginException("统一认证返回的用户名不合法");
        }
        for (int i = 0; i < username.length(); i++) {
            char ch = username.charAt(i);
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) {
                throw new SsoLoginException("统一认证返回的用户名不合法");
            }
        }
        return username;
    }

    private SysUser provision(String username, String role) {
        SysUser existing = findByUsername(username);
        if (existing == null) {
            SysUser created = createUser(username, role);
            try {
                sysUserMapper.insert(created);
                return created;
            } catch (DuplicateKeyException e) {
                // 并发首次登录：另一个请求已插入，转为更新路径
                existing = findByUsername(username);
                if (existing == null) {
                    throw new SsoLoginException("统一认证用户创建失败");
                }
            }
        }
        return updateUser(existing, role);
    }

    /**
     * 先精确匹配；未命中再按大小写不敏感回查。
     *
     * <p>MySQL 默认排序规则不区分大小写，而 H2/PostgreSQL 区分：若只做精确匹配，
     * {@code Admin} 会新建一个与本地 {@code admin} 并存的账号；同理 IdP 换大小写
     * 也会分叉出重复账号。回查时按 id 取最早一条，保证同一人只落一行。
     */
    private SysUser findByUsername(String username) {
        SysUser exact = sysUserMapper.selectOne(new QueryWrapper<SysUser>().eq("username", username));
        if (exact != null) {
            return exact;
        }
        List<SysUser> variants = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                .apply("LOWER(username) = {0}", username.toLowerCase(Locale.ROOT))
                .orderByAsc("id"));
        return variants == null || variants.isEmpty() ? null : variants.get(0);
    }

    private SysUser createUser(String username, String role) {
        LocalDateTime now = LocalDateTime.now();
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash("");
        user.setNickname(username);
        user.setRole(role);
        user.setSource(SOURCE_SSO);
        user.setIsEnabled(true);
        user.setIsPasswordDefault(false);
        user.setLastLoginAt(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    private SysUser updateUser(SysUser user, String role) {
        if (SOURCE_LOCAL.equalsIgnoreCase(user.getSource())) {
            throw new SsoLoginException("该用户名已被本地账号占用，请联系管理员");
        }
        if (!Boolean.TRUE.equals(user.getIsEnabled())) {
            throw new SsoLoginException("用户已被禁用");
        }
        if (SOURCE_REMOTE.equalsIgnoreCase(user.getSource())) {
            // 既有远端密码认证用户保持原来源标记，避免改口径
            user.setRole(role);
        } else {
            user.setRole(role);
            user.setSource(SOURCE_SSO);
        }
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            user.setNickname(user.getUsername());
        }
        LocalDateTime now = LocalDateTime.now();
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        sysUserMapper.updateById(user);
        return user;
    }

    public record SsoLoginResult(SysUser user, String token) {
    }

    public record TestIdentity(String username, String role, boolean adminMarker) {
    }
}
