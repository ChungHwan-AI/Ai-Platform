package com.buhmwoo.oneask.common.exception;

import com.buhmwoo.oneask.common.dto.ErrorCode;
import java.util.Map;

public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> details; // 선택: 필드 에러/컨텍스트

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    public BusinessException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details;
    }

    // 편의 팩토리
    public static BusinessException badRequest(String msg) { return new BusinessException(ErrorCode.BAD_REQUEST, msg); }
    public static BusinessException notFound(String msg)   { return new BusinessException(ErrorCode.NOT_FOUND, msg); }
    public static BusinessException conflict(String msg)   { return new BusinessException(ErrorCode.CONFLICT, msg); }
    public static BusinessException forbidden(String msg)  { return new BusinessException(ErrorCode.FORBIDDEN, msg); }
    public static BusinessException unauthorized(String msg){return new BusinessException(ErrorCode.UNAUTHORIZED, msg); }

    public ErrorCode getErrorCode() { return errorCode; }
    public Map<String, Object> getDetails() { return details; }
}
