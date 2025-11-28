package com.buhmwoo.oneask.modules.document.api.dto;

import com.buhmwoo.oneask.modules.document.application.question.BotMode; // ✅ 질문 모드를 명확히 표현하기 위해 enum 을 임포트합니다.
import jakarta.validation.constraints.NotBlank; // ✅ 필수 필드를 검증하기 위해 사용합니다.

/**
 * 문서 기반 질문 요청을 표현하는 DTO 입니다. // ✅ REST POST 요청 시 JSON 본문을 받아 직렬화합니다.
 */
public record QuestionRequestDto(
        @NotBlank(message = "question은 필수입니다.") String question, // ✅ 사용자가 입력한 질문 내용
        BotMode mode // ✅ RAG 동작 방식을 지정하는 모드 (null 허용 시 기본 STRICT 로 처리)
) {
}