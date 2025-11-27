package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 검색된 컨텍스트를 바탕으로 GPT와 같은 LLM을 호출하는 계약을 정의합니다. // ✅ 질문 응답 생성 단계를 모듈화하려는 목적을 설명합니다.
 */
public interface GptClient {

    /**
     * 질문과 컨텍스트를 전달해 최종 답변을 생성합니다. // ✅ 호출자가 원하는 답변 텍스트만 돌려받도록 메서드 시그니처를 단순화합니다.
     */
    GptResponse generate(GptRequest request);

    /**
     * 특정 타임아웃 한도 내에서 답변을 생성합니다. // ✅ 일반 질문과 일상 대화에 서로 다른 대기 시간을 적용하기 위한 오버로드입니다.
     */
    default GptResponse generate(GptRequest request, java.time.Duration timeout) {
        return generate(request); // ✅ 기본 구현은 기존 동작을 유지하며, 구현체에서 필요 시 오버라이드할 수 있습니다.
    }    
}