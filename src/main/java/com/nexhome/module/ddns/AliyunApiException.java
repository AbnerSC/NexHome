package com.nexhome.module.ddns;

/**
 * 阿里云 OpenAPI 业务错误（携带错误码）。
 * <p>
 * 由 HTTP 非 200 响应解析而来，替代原始错误报文，
 * 使任务失败状态中的错误信息（错误码 + 描述）更易读。
 */
public final class AliyunApiException extends IllegalStateException {

    /** 阿里云错误码，如 DomainRecordDuplicate */
    public final String code;

    public AliyunApiException(String code, String message) {
        super("阿里云API错误[" + code + "]: " + message);
        this.code = code;
    }
}
