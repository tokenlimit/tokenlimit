package com.tokenlimit.common.api;

import java.io.Serializable;

/**
 * 统一 API 返回结构.
 *
 * @param <T> 数据类型
 */
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 是否成功 */
    private boolean success;
    /** 业务码：0 成功，非 0 失败 */
    private int code;
    /** 提示信息 */
    private String message;
    /** 数据 */
    private T data;

    public Result() {
    }

    public Result(boolean success, int code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(true, 0, "ok", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, 0, "ok", data);
    }

    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(false, code, message, null);
    }

    public static <T> Result<T> failure(ErrorCode errorCode) {
        return new Result<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return "Result{success=" + success + ", code=" + code + ", message='" + message + "', data=" + data + '}';
    }
}
