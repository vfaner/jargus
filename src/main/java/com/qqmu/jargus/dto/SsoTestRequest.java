package com.qqmu.jargus.dto;

import lombok.Data;

/**
 * 统一认证测试请求：表单配置 + 可选真实票据。
 */
@Data
public class SsoTestRequest {

    private SsoConfigForm config;

    /** 可选；仅当管理员手头有真实票据时用于完整校验。 */
    private String ticket;
}
