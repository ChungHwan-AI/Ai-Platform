package com.buhmwoo.oneask.modules.document.domain;

/**
 * RAG 인덱싱 상태를 명확히 표현하기 위한 전용 열거형입니다. // ✅ 각 상태가 의미하는 바를 코드 수준에서 드러내기 위해 열거형으로 정의합니다.
 */
public enum DocumentIndexingStatus {
    PENDING,        // ✅ 업로드 직후 아직 인덱싱 요청을 보내지 않은 상태입니다.
    PROCESSING,     // ✅ 인덱싱 요청을 전송했고 결과를 기다리고 있는 상태입니다.
    SUCCEEDED,      // ✅ 인덱싱이 정상적으로 완료된 상태입니다.
    FAILED,         // ✅ 인덱싱이 오류로 인해 실패한 상태입니다.
    SKIPPED         // ✅ 환경 설정 등으로 인덱싱을 수행하지 않은 상태입니다.
}