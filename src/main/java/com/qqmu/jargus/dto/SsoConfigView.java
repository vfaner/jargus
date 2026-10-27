package com.qqmu.jargus.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业统一认证配置读取模型：不含共享密钥明文或密文，只暴露是否已配置。
 */
@Data
public class SsoConfigView {

    private boolean enabled;
    private String authMode;
    private String adminUsers;
    private String loginEntryUrl;
    private String logoutRedirectUrl;
    private boolean localLoginEnabled;
    private String userHeader;
    private String adminHeader;
    private String adminValue;
    private String secretHeaderName;
    private boolean secretConfigured;
    private String validateUrl;
    private String validateMethod;
    private String ticketParam;
    private String successField;
    private String successValue;
    private String userField;
    private String adminField;
    private int timeoutMs;
    private boolean allowInsecureHttp;
    private LocalDateTime updatedAt;
}
