package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 질문에 대한 벡터 검색을 수행하기 위한 입력 값을 표현합니다. // ✅ 검색 단계에 필요한 파라미터를 하나의 객체로 묶어 전달함을 설명합니다.
 */
public record DocumentRetrievalRequest(
        String question, // ✅ 사용자가 입력한 자연어 질문을 그대로 전달합니다.
        String docId, // ✅ 특정 문서로 검색 범위를 제한할 때 사용할 UUID입니다.
        int topK // ✅ 몇 개의 청크를 검색 결과로 가져올지 제어하는 파라미터입니다.
) {
}