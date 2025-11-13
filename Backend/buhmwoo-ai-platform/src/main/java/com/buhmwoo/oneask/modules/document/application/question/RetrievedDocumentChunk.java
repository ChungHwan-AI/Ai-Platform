package com.buhmwoo.oneask.modules.document.application.question;

import java.util.Map; // ✅ 청크 메타데이터를 표현하기 위해 Map을 임포트합니다.

/**
 * 벡터 검색 결과에서 반환되는 개별 청크의 정보를 표현합니다. // ✅ UI와 LLM 프롬프트 모두에서 활용될 필드를 정리합니다.
 */
public record RetrievedDocumentChunk(
        String reference, // ✅ [청크 N] 형태의 인용 라벨을 저장합니다.
        int chunkIndex, // ✅ 검색 결과 순번을 그대로 유지합니다.
        String content, // ✅ GPT 프롬프트에 투입할 청크 본문입니다.
        String preview, // ✅ 프런트에서 미리보기로 보여줄 짧은 요약 텍스트입니다.
        String source, // ✅ 원본 문서명 또는 식별자를 제공합니다.
        Integer page, // ✅ 페이지/슬라이드 번호 등 위치 정보를 담습니다.
        Map<String, Object> metadata // ✅ 추가 필드가 필요할 때 확장 가능하도록 메타데이터 맵을 제공합니다.
) {
}