package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 질문에 대한 유사도 검색을 수행하는 컴포넌트 계약을 정의합니다. // ✅ 컨트롤러/서비스와 검색 구현체를 느슨하게 연결하기 위한 인터페이스임을 설명합니다.
 */
public interface DocumentRetriever {

    /**
     * 질문과 선택적 문서 범위를 입력받아 검색 결과를 반환합니다. // ✅ 검색 단계 결과를 통일된 형태로 돌려줌을 명시합니다.
     */
    DocumentRetrievalResult retrieve(DocumentRetrievalRequest request);
}