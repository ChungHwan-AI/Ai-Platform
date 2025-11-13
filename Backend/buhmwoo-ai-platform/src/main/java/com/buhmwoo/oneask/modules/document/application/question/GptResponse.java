package com.buhmwoo.oneask.modules.document.application.question;

/**
 * GPT 호출 결과를 표현하는 단순 응답 모델입니다. // ✅ 추후 필드 확장 시에도 구조를 유지하기 위해 별도 타입으로 분리합니다.
 */
public record GptResponse(
        String answer // ✅ 생성된 자연어 답변 텍스트입니다.
) {
}