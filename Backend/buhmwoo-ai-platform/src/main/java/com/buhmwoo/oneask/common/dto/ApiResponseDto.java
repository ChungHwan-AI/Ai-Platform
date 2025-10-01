package com.buhmwoo.oneask.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResponseDto<T> {
    private boolean success;
    private String message;
    private T data;

    // ✅ 성공 응답
    public static <T> ApiResponseDto<T> ok(T data) {
        return new ApiResponseDto<>(true, "success", data);
    }

    // ✅ 성공 응답 (메시지 커스텀)
    public static <T> ApiResponseDto<T> ok(T data, String message) {
        return new ApiResponseDto<>(true, message, data);
    }

    // ✅ 실패 응답 (데이터 없음)
    public static <T> ApiResponseDto<T> fail(String message) {
        return new ApiResponseDto<>(false, message, null);
    }

    // ✅ 실패 응답 (데이터 포함)
    public static <T> ApiResponseDto<T> fail(String message, T data) {
        return new ApiResponseDto<>(false, message, data);
    }
}
