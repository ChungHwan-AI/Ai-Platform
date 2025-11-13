package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 검색된 컨텍스트를 바탕으로 GPT와 같은 LLM을 호출하는 계약을 정의합니다. // ✅ 질문 응답 생성 단계를 모듈화하려는 목적을 설명합니다.
 */
public interface GptClient {

    /**
     * 질문과 컨텍스트를 전달해 최종 답변을 생성합니다. // ✅ 호출자가 원하는 답변 텍스트만 돌려받도록 메서드 시그니처를 단순화합니다.
     */
    GptResponse generate(GptRequest request);
}