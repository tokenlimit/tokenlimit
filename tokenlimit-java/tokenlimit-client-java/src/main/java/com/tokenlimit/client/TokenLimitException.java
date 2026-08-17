package com.tokenlimit.client;

/**
 * 客户端异常：网络错误、服务端错误码、配额被拒等.
 */
public class TokenLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public TokenLimitException(String message) {
        super(message);
        this.code = -1;
    }

    public TokenLimitException(int code, String message) {
        super(message);
        this.code = code;
    }

    public TokenLimitException(String message, Throwable cause) {
        super(message, cause);
        this.code = -1;
    }

    public int getCode() {
        return code;
    }
}
