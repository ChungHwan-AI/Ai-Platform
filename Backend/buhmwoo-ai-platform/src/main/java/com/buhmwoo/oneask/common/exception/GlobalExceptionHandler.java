package com.buhmwoo.oneask.common.exception;

import com.buhmwoo.oneask.common.dto.ApiError;
import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Optional;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ========= 도메인/커스텀 ========= */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleBusiness(BusinessException ex, HttpServletRequest req) {
        log.warn("[BUSINESS] {} - {}", req.getRequestURI(), ex.getMessage());
        return buildError(
                HttpStatus.BAD_REQUEST,
                ex.getErrorCode(),
                ex.getMessage(),
                req,
                ex.getDetails()
        );
    }

    /* ========= Validation ========= */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                              HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field", f.getField());
                    m.put("message", f.getDefaultMessage());
                    m.put("rejected", f.getRejectedValue());
                    return m;
                }).collect(Collectors.toList());
        details.put("fieldErrors", fieldErrors);

        log.debug("[VALIDATION] {} - {}", req.getRequestURI(), fieldErrors);
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Validation failed", req, details);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleBind(BindException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field", f.getField());
                    m.put("message", f.getDefaultMessage());
                    m.put("rejected", f.getRejectedValue());
                    return m;
                }).collect(Collectors.toList());
        details.put("fieldErrors", fieldErrors);

        log.debug("[BIND] {} - {}", req.getRequestURI(), fieldErrors);
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Binding failed", req, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleConstraintViolation(ConstraintViolationException ex,
                                                                           HttpServletRequest req) {
        List<Map<String, Object>> violations = ex.getConstraintViolations()
                .stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", v.getPropertyPath() == null ? null : v.getPropertyPath().toString());
                    m.put("message", v.getMessage());
                    m.put("invalid", safeInvalidValue(v));
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("violations", violations);

        log.debug("[CONSTRAINT] {} - {}", req.getRequestURI(), violations);
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Constraint violation", req, details);
    }

    /* ========= Bad Request 계열 ========= */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleMissingParam(MissingServletRequestParameterException ex,
                                                                    HttpServletRequest req) {
        Map<String, Object> details = Map.of("parameterName", ex.getParameterName(), "parameterType", ex.getParameterType());
        log.debug("[MISSING-PARAM] {} - {}", req.getRequestURI(), details);
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage(), req, details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest req) {

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", ex.getName());
        details.put("requiredType", 
            Optional.ofNullable(ex.getRequiredType())
                    .map(Class::getSimpleName)
                    .orElse(null)
        );
        details.put("value", ex.getValue());

        log.debug("[TYPE-MISMATCH] {} - {}", req.getRequestURI(), details);
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Parameter type mismatch", req, details);
    }


    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleNotReadable(HttpMessageNotReadableException ex,
                                                                   HttpServletRequest req) {
        log.debug("[NOT-READABLE] {} - {}", req.getRequestURI(), ex.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Malformed JSON request", req, null);
    }

    /* ========= 405/415 ========= */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                          HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("method", ex.getMethod());
        // 지원 메서드 집합을 문자열 리스트로 변환하여 직렬화 오류를 방지
        details.put("supported", Optional.ofNullable(ex.getSupportedHttpMethods())
                .map(methods -> methods.stream().map(HttpMethod::name).toList())
                .orElse(null));
        log.debug("[METHOD-NOT-SUPPORTED] {} - {}", req.getRequestURI(), details);
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.BAD_REQUEST, ex.getMessage(), req, details);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                             HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("contentType", ex.getContentType());
        details.put("supported", ex.getSupportedMediaTypes());
        log.debug("[MEDIA-NOT-SUPPORTED] {} - {}", req.getRequestURI(), details);
        return buildError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.BAD_REQUEST, ex.getMessage(), req, details);
    }

    /* ========= DB/SQL ========= */
    @ExceptionHandler({DataAccessException.class})
    public ResponseEntity<ApiResponseDto<ApiError>> handleDataAccess(DataAccessException ex, HttpServletRequest req) {
        log.error("[DATA-ACCESS] {} - {}", req.getRequestURI(), summarize(ex));
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SQL_ERROR, "Database access error", req, null);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleSql(SQLException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sqlState", ex.getSQLState());
        details.put("errorCode", ex.getErrorCode());

        log.error("[SQL] {} - state={}, code={}, msg={}", req.getRequestURI(), ex.getSQLState(), ex.getErrorCode(), ex.getMessage());
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SQL_ERROR, "SQL error", req, details);
    }

    /* ========= Fallback ========= */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDto<ApiError>> handleException(Exception ex, HttpServletRequest req) {
        String path = req.getRequestURI();

        // ✅ Swagger & springdoc 경로는 제외
        if (path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui")) {
            throw new RuntimeException(ex);
        }

        log.error("[UNCAUGHT] {} - {}", req.getRequestURI(), summarize(ex), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Internal server error", req, null);
    }

    /* ========= 공통 빌더 ========= */
    private ResponseEntity<ApiResponseDto<ApiError>> buildError(HttpStatus status,
                                                             ErrorCode code,
                                                             String message,
                                                             HttpServletRequest req,
                                                             Map<String, Object> details) {
        ApiError body = new ApiError(code.getCode(), message, req.getRequestURI(), details);
        ApiResponseDto<ApiError> response = ApiResponseDto.fail(message, body);
        return ResponseEntity.status(status).body(response);
    }

    private Object safeInvalidValue(ConstraintViolation<?> v) {
        Object raw = v.getInvalidValue();
        if (raw == null) return null;
        String s = String.valueOf(raw);
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private String summarize(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) return t.getClass().getSimpleName();
        return msg.length() > 1000 ? msg.substring(0, 1000) + "..." : msg;
    }
}
