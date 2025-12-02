package com.buhmwoo.oneask.modules.document.application.question;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * GPT를 활용해 사용자 질문의 의도를 판별하는 기본 구현체입니다.
 */
@Component
public class GptQuestionIntentClassifier implements QuestionIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(GptQuestionIntentClassifier.class);

    private final GptClient gptClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GptQuestionIntentClassifier(GptClient gptClient) {
        this.gptClient = gptClient;
    }

    @Override
    public QuestionIntentResult classify(String question, String docId) {
        // 빈 질문이면 그냥 UNKNOWN 으로 즉시 반환
        if (question == null || question.isBlank()) {
            return QuestionIntentResult.fallback(QuestionIntent.UNKNOWN, docId != null);
        }

        try {
            // 🔹 의도 분류는 "있으면 좋은" 보조 기능이므로 타임아웃 없이 호출
            //    → 느리면 느린 대로 기다리고, 실패하면 아래 catch 에서 fallback 처리
            GptRequest req = new GptRequest(question, buildSystemPrompt(docId));
            GptResponse response = gptClient.generate(req);

            QuestionIntentResult parsed = parseIntent(response.answer(), docId);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception e) {
            // 🔹 스택까지 함께 남겨서 나중에 원인 추적하기 쉽게
            log.warn("[INTENT][FAIL] GPT 분류 실패: {}", e.getMessage(), e);
        }

        // 🔹 GPT 호출 실패 / 파싱 실패 시에도 서비스가 죽지 않도록 항상 fallback 으로 마무리
        return fallbackIntent(question, docId);
    }

    /**
     * GPT가 내려준 JSON 응답에서 intent/needsDocumentContext를 파싱합니다.
     */
    private QuestionIntentResult parseIntent(String rawAnswer, String docId) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return null;
        }

        String trimmed = rawAnswer.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // JSON 형식이 아니면 GPT 응답을 신뢰하지 않고 fallback 으로 넘김
            return null;
        }

        String jsonCandidate = trimmed.substring(start, end + 1);
        try {
            JsonNode node = objectMapper.readTree(jsonCandidate);

            String intentText = Optional.ofNullable(node.get("intent"))
                    .map(JsonNode::asText)
                    .orElse("");

            QuestionIntent intent = toIntent(intentText);

            boolean needsDocumentContext = Optional.ofNullable(node.get("needsDocumentContext"))
                    .map(JsonNode::asBoolean)
                    .orElse(docId != null);

            if (intent == null) {
                return null;
            }

            return QuestionIntentResult.of(intent, needsDocumentContext);
        } catch (Exception e) {
            // 파싱 실패는 그냥 디버그 수준으로만 기록하고 fallback 으로
            log.debug("[INTENT][PARSE] 응답 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private QuestionIntent toIntent(String intentText) {
        if (intentText == null) {
            return null;
        }
        try {
            return QuestionIntent.valueOf(intentText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * GPT 호출/파싱이 실패했을 때 사용하는 보수적인 fallback 규칙입니다.
     */
    private QuestionIntentResult fallbackIntent(String question, String docId) {
        boolean hasDocId = docId != null;

        // 문서 지정이 없고, 전형적인 스몰톡 패턴이면 SMALL_TALK 로
        if (isLikelySmallTalk(question) && !hasDocId) {
            return QuestionIntentResult.fallback(QuestionIntent.SMALL_TALK, false);
        }

        // 그 외에는 문서가 있으면 DOC_KNOWLEDGE, 없으면 GENERAL_KNOWLEDGE 로 기본 처리
        QuestionIntent defaultIntent = hasDocId
                ? QuestionIntent.DOC_KNOWLEDGE
                : QuestionIntent.GENERAL_KNOWLEDGE;

        return QuestionIntentResult.fallback(defaultIntent, hasDocId);
    }

    /**
     * GPT에게 "의도만 JSON으로 달라"라고 지시하는 시스템 프롬프트를 구성합니다.
     */
    private String buildSystemPrompt(String docId) {
        String target = (docId == null)
                ? "(특정 문서 없음)"
                : "(문서 ID: " + docId + ")";

        return "너는 질문 라우팅을 담당하는 분류기야. 질문에 대한 답변은 하지 말고 intent만 JSON으로 반환해. "
                + "반환 JSON 키: intent(필수), needsDocumentContext(불리언). 가능한 intent 값: SMALL_TALK, DOC_KNOWLEDGE, GENERAL_KNOWLEDGE, UNKNOWN. "
                + "SMALL_TALK=인사/잡담/날씨/기분 등 일상 대화, DOC_KNOWLEDGE=업로드된 문서나 DB 내용 기반 질문, "
                + "GENERAL_KNOWLEDGE=외부 상식 기반 질문, UNKNOWN=판단 불가. "
                + "문서 대상 정보: " + target + ". 질문 언어를 그대로 유지하고 추가 설명이나 코드 블록 없이 JSON만 답변해.";
    }

    /**
     * 간단한 패턴 기반으로 스몰톡 여부를 추정합니다.
     */
    private boolean isLikelySmallTalk(String question) {
        if (question == null) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return normalized.contains("날씨")
                || normalized.contains("안녕")
                || normalized.contains("hello")
                || normalized.contains("hi")
                || normalized.contains("고마워")
                || normalized.contains("thank")
                || normalized.contains("기분")
                || normalized.contains("오늘 어때");
    }
}
