package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.RemoteAuthConfig;
import com.qqmu.jargus.entity.SysUser;
import com.qqmu.jargus.mapper.SysUserMapper;
import com.qqmu.jargus.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;
    private final RemoteAuthConfigService remoteAuthConfigService;
    private final SsoConfigService ssoConfigService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 本地登录
     */
    public LoginResult loginLocal(String username, String password) {
        if (!ssoConfigService.isLocalLoginEnabled()) {
            throw new RuntimeException("本地登录已关闭，请使用企业统一认证");
        }
        SysUser user = sysUserMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", username)
        );
        if (user == null) {
            throw new RuntimeException("用户名或密码错误");
        }
        if (!"LOCAL".equalsIgnoreCase(user.getSource())) {
            throw new RuntimeException("SSO".equalsIgnoreCase(user.getSource())
                    ? "该用户需通过企业统一认证登录"
                    : "该用户需通过远端登录");
        }
        if (!Boolean.TRUE.equals(user.getIsEnabled())) {
            throw new RuntimeException("用户已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("用户名或密码错误");
        }

        // 更新最后登录时间
        user.setLastLoginAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResult(token, toUserInfo(user));
    }

    private SysUserInfo toUserInfo(SysUser user) {
        SysUserInfo info = new SysUserInfo();
        info.setId(user.getId());
        info.setUsername(user.getUsername());
        info.setNickname(user.getNickname());
        info.setRole(user.getRole());
        info.setSource(user.getSource());
        info.setPasswordDefault(Boolean.TRUE.equals(user.getIsPasswordDefault()));
        return info;
    }

    /**
     * 远端登录（OA 对接）
     *
     * 根据管理员配置的远端认证信息调用 OA 登录接口，校验通过后：
     * - 对端用户已在本地存在 → 更新昵称/角色后签发 token
     * - 首次登录 → 自动创建 source=REMOTE 的用户
     */
    public LoginResult loginRemote(String username, String password, Long configId) {
        RemoteAuthConfig config = requireEnabledConfig(configId);

        // 调用 OA 登录接口
        Map<String, Object> resp = remoteAuthConfigService.invokeLogin(config, username, password);
        if (resp == null) {
            throw new RuntimeException("远端认证服务无响应");
        }
        String remoteUsername = resolveRemoteUsername(resp, config, username);
        String nickname = resolveNickname(resp, config, remoteUsername);
        String role = mapRemoteRole(config, RemoteAuthConfigService.extractField(resp, config.getRoleField()));

        // 查询或自动创建本地用户
        SysUser user = upsertRemoteUser(remoteUsername, nickname, role);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginResult(token, toUserInfo(user));
    }

    /** 远端登录配置校验：必选、存在、已启用、loginUrl 已配置 */
    private RemoteAuthConfig requireEnabledConfig(Long configId) {
        if (configId == null) {
            throw new RuntimeException("请选择远端登录方式");
        }
        RemoteAuthConfig config = remoteAuthConfigService.getRawById(configId);
        if (config == null || !Boolean.TRUE.equals(config.getIsEnabled())) {
            throw new RuntimeException("远端登录方式不可用");
        }
        if (config.getLoginUrl() == null || config.getLoginUrl().isBlank()) {
            throw new RuntimeException("远端登录接口未配置");
        }
        return config;
    }

    /** 远端用户名：按 usernameField 取值；缺失时透传 OA 侧错误信息后抛异常 */
    private String resolveRemoteUsername(Map<String, Object> resp, RemoteAuthConfig config, String username) {
        Object remoteUserObj = config.getUsernameField() != null && !config.getUsernameField().isBlank()
                ? RemoteAuthConfigService.extractField(resp, config.getUsernameField())
                : username;
        if (remoteUserObj != null && !remoteUserObj.toString().isBlank()) {
            return remoteUserObj.toString();
        }
        // 尝试透传 OA 侧返回的错误信息
        Object errMsg = resp.get("message");
        if (errMsg == null) errMsg = resp.get("msg");
        if (errMsg == null) errMsg = resp.get("error");
        throw new RuntimeException(errMsg != null && !errMsg.toString().isBlank()
                ? "远端登录失败：" + errMsg
                : "远端登录失败：返回结果中未找到用户信息");
    }

    /** 昵称：优先 nicknameField，空则回退用户名 */
    private String resolveNickname(Map<String, Object> resp, RemoteAuthConfig config, String remoteUsername) {
        Object nickObj = RemoteAuthConfigService.extractField(resp, config.getNicknameField());
        if (nickObj != null && !nickObj.toString().isBlank()) {
            return nickObj.toString();
        }
        return remoteUsername;
    }

    /** 查询或自动创建本地远端用户，并回写昵称/角色/登录时间 */
    private SysUser upsertRemoteUser(String remoteUsername, String nickname, String role) {
        SysUser user = sysUserMapper.selectOne(
                new QueryWrapper<SysUser>().eq("username", remoteUsername)
        );
        LocalDateTime now = LocalDateTime.now();
        if (user == null) {
            user = new SysUser();
            user.setUsername(remoteUsername);
            user.setPasswordHash(""); // 远端用户无本地密码
            user.setSource("REMOTE");
            user.setIsEnabled(true);
            user.setIsPasswordDefault(false);
            user.setCreatedAt(now);
        }
        user.setNickname(nickname);
        user.setRole(role);
        user.setLastLoginAt(now);
        user.setUpdatedAt(now);
        if (user.getId() == null) {
            sysUserMapper.insert(user);
        } else {
            sysUserMapper.updateById(user);
        }
        return user;
    }

    /**
     * 根据 roleMapping（JSON: {"oaRole":"ADMIN"}）映射远端角色，默认 VIEWER
     */
    private String mapRemoteRole(RemoteAuthConfig config, Object remoteRole) {
        if (remoteRole == null || config.getRoleMapping() == null || config.getRoleMapping().isBlank()) {
            return "VIEWER";
        }
        try {
            Map<String, String> mapping = new ObjectMapper()
                    .readValue(config.getRoleMapping(), new TypeReference<Map<String, String>>() {});
            String mapped = mapping.get(remoteRole.toString());
            return mapped != null ? mapped : "VIEWER";
        } catch (Exception e) {
            log.warn("解析角色映射失败: {}", e.getMessage());
            return "VIEWER";
        }
    }

    /**
     * 修改密码
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }
        if (!"LOCAL".equalsIgnoreCase(user.getSource())) {
            throw new RuntimeException("远端用户不支持修改密码");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("原密码错误");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new RuntimeException("新密码长度不能少于6位");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setIsPasswordDefault(false);
        user.setUpdatedAt(LocalDateTime.now());
        return sysUserMapper.updateById(user) > 0;
    }

    /**
     * 获取当前用户信息
     */
    public SysUserInfo getUserInfo(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : toUserInfo(user);
    }

    // ===== 登录结果
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class LoginResult {
        private String token;
        private SysUserInfo user;
    }

    // ===== 用户信息（不含密码）
    @lombok.Data
    public static class SysUserInfo {
        private Long id;
        private String username;
        private String nickname;
        private String role;
        private String source;
        private boolean passwordDefault;
    }

    /**
     * 初始化默认用户（如果不存在）
     */
    public void initDefaultUsers() {
        // 检查 admin 是否存在
        Long adminCount = sysUserMapper.selectCount(
                new QueryWrapper<SysUser>().eq("username", "admin")
        );
        if (adminCount == null || adminCount == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("123456"));
            admin.setNickname("管理员");
            admin.setRole("ADMIN");
            admin.setSource("LOCAL");
            admin.setIsEnabled(true);
            admin.setIsPasswordDefault(true);
            admin.setCreatedAt(LocalDateTime.now());
            admin.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(admin);
            log.info("已创建默认管理员账号: admin/123456");
        }

        Long viewerCount = sysUserMapper.selectCount(
                new QueryWrapper<SysUser>().eq("username", "view")
        );
        if (viewerCount == null || viewerCount == 0) {
            SysUser viewer = new SysUser();
            viewer.setUsername("view");
            viewer.setPasswordHash(passwordEncoder.encode("123456"));
            viewer.setNickname("只读用户");
            viewer.setRole("VIEWER");
            viewer.setSource("LOCAL");
            viewer.setIsEnabled(true);
            viewer.setIsPasswordDefault(true);
            viewer.setCreatedAt(LocalDateTime.now());
            viewer.setUpdatedAt(LocalDateTime.now());
            sysUserMapper.insert(viewer);
            log.info("已创建默认只读账号: view/123456");
        }
    }
}
