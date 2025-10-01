package com.buhmwoo.oneask.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Map;

/**
 * API 에러 응답 표준 객체.
 * - 모든 예외 응답은 ApiResponse< ApiError > 형태로 내려가며,
 *   프론트/관측(로그 수집)에서 일관된 스키마로 처리할 수 있습니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({ "code", "message", "path", "timestamp", "traceId", "details" })
public class ApiError {

    /** 애플리케이션 표준 에러 코드 (예: E400, E500-SQL) */
    private final String code;

    /** 사람 친화적 메시지(로깅/알림에 바로 사용 가능) */
    private final String message;

    /** 요청 경로(에러가 발생한 엔드포인트) */
    private final String path;

    /** 서버 기준 에러 발생 시각(UTC) */
    private final Instant timestamp;

    /** 분산 추적/상관관계 식별자(MDC/헤더 연계 시 사용) */
    private final String traceId;

    /** 필드 에러 등 상세 컨텍스트(payload) */
    private final Map<String, Object> details;

    /* ===== 생성자 ===== */

    /**
     * 기본 생성자(Jackson용). 외부에서 직접 사용하지 않습니다.
     */
    protected ApiError() {
        this.code = null;
        this.message = null;
        this.path = null;
        this.timestamp = Instant.now();
        this.traceId = null;
        this.details = null;
    }

    /**
     * 표준 생성자.
     */
    public ApiError(String code, String message, String path, Map<String, Object> details) {
        this(code, message, path, details, null);
    }

    /**
     * 표준 생성자(TraceId 포함).
     */
    public ApiError(String code, String message, String path, Map<String, Object> details, String traceId) {
        this.code = code;
        this.message = message;
        this.path = path;
        this.details = details;
        this.traceId = traceId;
        this.timestamp = Instant.now();
    }

    /* ===== 정적 팩토리 ===== */

    public static ApiError of(ErrorCode errorCode, String message, String path) {
        return new ApiError(errorCode.getCode(), message, path, null, null);
    }

    public static ApiError of(ErrorCode errorCode, String message, String path, Map<String, Object> details) {
        return new ApiError(errorCode.getCode(), message, path, details, null);
    }

    public static ApiError of(ErrorCode errorCode, String message, String path, Map<String, Object> details, String traceId) {
        return new ApiError(errorCode.getCode(), message, path, details, traceId);
    }

    /* ===== Getters ===== */

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getTraceId() {
        return traceId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
