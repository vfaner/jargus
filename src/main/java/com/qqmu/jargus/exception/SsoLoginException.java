package com.qqmu.jargus.exception;

/**
 * 企业统一认证的可预期失败。message 是可直接展示给用户的安全文案，
 * 不包含票据、共享密钥或对端响应原文。
 */
public class SsoLoginException extends RuntimeException {

    public SsoLoginException(String message) {
        super(message);
    }

    public SsoLoginException(String message, Throwable cause) {
        super(message, cause);
    }
}
