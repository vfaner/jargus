package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.SsoConfig;

import java.util.Map;

/**
 * 登录页展示状态推导（纯函数，便于单测）。
 *
 * <p>只有在「票据模式 + 本地登录关闭 + 没有启用的密码认证源 + 没有 SSO 错误参数」时
 * 才自动跳转企业登录中心；只要存在任一其它入口或错误，就留在登录页给出可操作选项。
 */
public final class SsoLoginPageResolver {

    private SsoLoginPageResolver() {
    }

    public static LoginPageState resolve(SsoConfig config,
                                         boolean localLoginEnabled,
                                         boolean hasRemoteConfigs,
                                         Map<String, String> params) {
        String mode = SsoConfigService.upper(config == null ? null : config.getAuthMode());
        boolean ssoOn = config != null
                && Boolean.TRUE.equals(config.getIsEnabled())
                && SsoConfigService.isSsoUsable(config);
        boolean ticketMode = ssoOn && SsoConfig.MODE_TICKET.equals(mode);
        boolean headerMode = ssoOn && SsoConfig.MODE_HEADER.equals(mode);
        boolean hasEntryUrl = config != null && !SsoConfigService.isBlank(config.getLoginEntryUrl());

        String errorKey = errorKey(params);
        boolean showSsoButton = ticketMode && hasEntryUrl;
        boolean redirectToSso = showSsoButton && !localLoginEnabled && !hasRemoteConfigs && errorKey == null;

        return new LoginPageState(
                ssoOn,
                mode,
                hasEntryUrl ? config.getLoginEntryUrl() : "",
                showSsoButton,
                localLoginEnabled,
                hasRemoteConfigs,
                headerMode,
                errorKey,
                redirectToSso);
    }

    private static String errorKey(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        if (params.containsKey("ssoTicketMissing")) return "login.sso.error.ticketMissing";
        if (params.containsKey("ssoTicketError")) return "login.sso.error.ticketError";
        if (params.containsKey("ssoTooManyAttempts")) return "login.sso.error.tooManyAttempts";
        if (params.containsKey("ssoDisabled")) return "login.sso.error.disabled";
        if (params.containsKey("ssoHeaderError")) return "login.sso.error.header";
        return null;
    }

    public record LoginPageState(boolean ssoEnabled,
                                 String ssoMode,
                                 String ssoEntryUrl,
                                 boolean showSsoButton,
                                 boolean showLocalForm,
                                 boolean showRemoteTab,
                                 boolean showHeaderNotice,
                                 String ssoErrorKey,
                                 boolean redirectToSso) {
    }
}
