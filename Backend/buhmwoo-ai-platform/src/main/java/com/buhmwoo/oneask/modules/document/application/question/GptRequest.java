package com.buhmwoo.oneask.modules.document.application.question;

/**
 * GPT 호출 시 필요한 질문과 컨텍스트 정보를 묶어 전달합니다. // ✅ LLM 호출 파라미터를 명확히 하기 위해 별도 레코드로 분리합니다.
 */
public record GptRequest(
        String question, // ✅ 사용자 질문 원문입니다.
        String context // ✅ 검색 단계에서 조합된 컨텍스트 텍스트입니다.
) {
}