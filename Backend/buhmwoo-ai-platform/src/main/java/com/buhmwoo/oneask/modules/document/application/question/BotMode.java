package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 봇이 답변을 생성할 때 사용할 동작 모드입니다. // ✅ 검색 점수에 따라 fallback 전략을 전환하기 위한 설정임을 명시합니다.
 */
public enum BotMode { // ✅ Enum 을 사용해 허용 가능한 모드 값을 명확히 제한합니다.
    STRICT,      // ✅ 문서/DB 근거가 없으면 답변을 거부하는 모드입니다.
    HYBRID_AUTO  // ✅ 문서가 없을 때 일반 지식 기반 답변으로 자동 전환하는 모드입니다.
}