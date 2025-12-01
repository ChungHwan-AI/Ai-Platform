package com.buhmwoo.oneask.modules.document.application.question;

/**
 * 사용자 질문의 의도를 판별하기 위한 분류기 계약입니다.
 */
public interface QuestionIntentClassifier {

    /**
     * 질문과 문서 ID 정보를 기반으로 질문 의도를 분류합니다.
     *
     * @param question 사용자가 입력한 질문 텍스트
     * @param docId 질문 대상 문서 ID(없을 수 있음)
     * @return 분류 결과와 부가 정보
     */
    QuestionIntentResult classify(String question, String docId);
}   