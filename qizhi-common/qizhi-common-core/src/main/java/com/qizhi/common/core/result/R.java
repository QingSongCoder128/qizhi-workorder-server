package com.qizhi.common.core.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 统一响应封装
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class R<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return new R<>(200, "success", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return new R<>(200, msg, data);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(500, msg, null);
    }

    public static <T> R<T> fail(Integer code, String msg) {
        return new R<>(code, msg, null);
    }

    // ========== 常用业务错误码 ==========

    /** 401 未登录 */
    public static <T> R<T> unauthorized(String msg) {
        return new R<>(401, msg, null);
    }

    /** 403 无权限 */
    public static <T> R<T> forbidden(String msg) {
        return new R<>(403, msg, null);
    }

    /** 404 资源不存在 */
    public static <T> R<T> notFound(String msg) {
        return new R<>(404, msg, null);
    }

    /** 409 冲突（如重复提交） */
    public static <T> R<T> conflict(String msg) {
        return new R<>(409, msg, null);
    }

    /** 429 限流 */
    public static <T> R<T> tooMany(String msg) {
        return new R<>(429, msg, null);
    }
}
