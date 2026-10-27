package com.qqmu.jargus.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 统一认证设置页数据：配置视图 + 只读的受信代理列表。
 */
@Data
@AllArgsConstructor
public class SsoSettingsResponse {

    private SsoConfigView config;
    private List<String> trustedProxies;
}
