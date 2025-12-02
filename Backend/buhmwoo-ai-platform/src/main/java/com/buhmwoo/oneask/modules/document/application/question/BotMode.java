package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 봇이 답변을 생성할 때 사용할 동작 모드입니다. // ✅ 검색 점수에 따라 fallback 전략을 전환하기 위한 설정임을 명시합니다.
 */
public enum BotMode { // ✅ Enum 을 사용해 허용 가능한 모드 값을 명확히 제한합니다.
    STRICT,       // 문서만
    GENERAL,      // 일반 GPT (웹서치 포함)
    HYBRID        // 문서 + 웹서치
}