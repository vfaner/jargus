package com.qqmu.jargus.dto;

import lombok.Data;

/**
 * 企业统一认证配置表单。
 *
 * <p>{@code secretHeaderValue} 是明文输入框：非空且不含 {@code ****} 时加密保存，
 * 空或掩码表示保留已配置密钥。任何读取接口都不返回该字段。
 */
@Data
public class SsoConfigForm {

    private Boolean isEnabled;
    private String authMode;
    private String adminUsers;
    private String loginEntryUrl;
    private String logoutRedirectUrl;
    private Boolean localLoginEnabled;
    private String userHeader;
    private String adminHeader;
    private String adminValue;
    private String secretHeaderName;
    private String secretHeaderValue;
    private String validateUrl;
    private String validateMethod;
    private String ticketParam;
    private String successField;
    private String successValue;
    private String userField;
    private String adminField;
    private Integer timeoutMs;
    private Boolean allowInsecureHttp;
}
