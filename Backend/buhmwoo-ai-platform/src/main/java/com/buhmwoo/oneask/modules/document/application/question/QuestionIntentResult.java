package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 질문 분류기에서 반환하는 분류 결과와 부가 정보를 담는 레코드입니다.
 *
 * @param intent 분류된 질문 의도
 * @param needsDocumentContext 문서 컨텍스트가 필요한지 여부
 * @param fromFallback GPT 분류 실패 시 기본/휴리스틱으로 판단했는지 여부
 */
public record QuestionIntentResult(
        QuestionIntent intent,
        boolean needsDocumentContext,
        boolean fromFallback
) {
    public static QuestionIntentResult of(QuestionIntent intent, boolean needsDocumentContext) {
        return new QuestionIntentResult(intent, needsDocumentContext, false);
    }

    public static QuestionIntentResult fallback(QuestionIntent intent, boolean needsDocumentContext) {
        return new QuestionIntentResult(intent, needsDocumentContext, true);
    }
}