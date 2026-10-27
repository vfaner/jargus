package com.qqmu.jargus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业统一认证配置（固定单行 id=1；无行 = 未配置）。
 *
 * <p>{@code auth_mode} 取 {@code TICKET}（企业登录中心票据回调）或
 * {@code HEADER}（受信网关注入身份头），避免使用数据库保留字 MODE。
 */
@Data
@TableName("sso_config")
public class SsoConfig {

    public static final long SINGLETON_ID = 1L;

    public static final String MODE_TICKET = "TICKET";
    public static final String MODE_HEADER = "HEADER";

    /** 固定主键，不能使用数据库自增。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** SSO 总开关。 */
    private Boolean isEnabled;

    /** TICKET / HEADER。 */
    private String authMode;

    /** 管理员用户名白名单，逗号分隔；忽略大小写。 */
    private String adminUsers;

    /** 企业登录中心地址（登录页按钮 / 仅 SSO 时自动跳转）。 */
    private String loginEntryUrl;

    /** 登出后回跳地址；空表示回本地 /login。 */
    private String logoutRedirectUrl;

    /** 是否允许本地账号密码登录。 */
    private Boolean localLoginEnabled;

    /** HEADER 模式：用户名头。 */
    private String userHeader;

    /** HEADER 模式：管理员标记头。 */
    private String adminHeader;

    /** HEADER 模式：管理员标记匹配值。 */
    private String adminValue;

    /** HEADER 模式：共享密钥头名；空表示不校验。 */
    private String secretHeaderName;

    /** HEADER 模式：共享密钥密文（AES，永不明文落库/回显）。 */
    private String secretHeaderValue;

    /** TICKET 模式：票据校验接口。 */
    private String validateUrl;

    /** TICKET 模式：GET / POST。 */
    private String validateMethod;

    /** TICKET 模式：票据参数名。 */
    private String ticketParam;

    /** 校验响应成功标志字段路径；空表示只要求用户名存在。 */
    private String successField;

    /** 校验响应成功标志匹配值。 */
    private String successValue;

    /** 校验响应用户名字段路径。 */
    private String userField;

    /** 校验响应管理员标记字段路径；空表示只看白名单。 */
    private String adminField;

    /** 校验请求超时（毫秒）。 */
    private Integer timeoutMs;

    /** 是否允许 http:// 端点。 */
    private Boolean allowInsecureHttp;

    private LocalDateTime updatedAt;
}
