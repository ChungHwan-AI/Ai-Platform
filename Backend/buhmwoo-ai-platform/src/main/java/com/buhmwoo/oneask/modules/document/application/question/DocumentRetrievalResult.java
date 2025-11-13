package com.buhmwoo.oneask.modules.document.application.question;

import java.util.List; // ✅ 검색 결과 청크 리스트를 표현하기 위해 List를 임포트합니다.

/**
 * 검색 단계의 결과로 얻은 컨텍스트와 청크 정보를 담습니다. // ✅ 추후 GPT 호출에 필요한 데이터를 포함함을 설명합니다.
 */
public record DocumentRetrievalResult(
        String context, // ✅ GPT 프롬프트에 사용할 통합 컨텍스트 텍스트입니다.
        List<RetrievedDocumentChunk> matches // ✅ UI 및 응답에 노출할 검색 청크 메타데이터입니다.
) {
}