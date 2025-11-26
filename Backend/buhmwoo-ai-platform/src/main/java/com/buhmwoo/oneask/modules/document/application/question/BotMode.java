package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 봇이 답변을 생성할 때 사용할 동작 모드입니다. // ✅ 검색 점수에 따라 fallback 전략을 전환하기 위한 설정임을 명시합니다.
 */
public enum BotMode { // ✅ Enum 을 사용해 허용 가능한 모드 값을 명확히 제한합니다.
    STRICT,      // ✅ 문서/DB 근거가 없으면 답변을 거부하는 모드입니다.
    HYBRID,      // ✅ 프런트엔드에서 사용하는 값과 동일하게 맞춰 하이브리드 모드를 표현합니다.
    HYBRID_AUTO  // ✅ 이전 이름을 유지해도 파싱되도록 남겨둔 호환용 항목입니다.
}