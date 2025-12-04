package com.buhmwoo.oneask.modules.document.infrastructure.client;

import com.buhmwoo.oneask.common.config.OneAskProperties;
import com.buhmwoo.oneask.modules.document.application.question.GptClient;
import com.buhmwoo.oneask.modules.document.application.question.GptRequest;
import com.buhmwoo.oneask.modules.document.application.question.GptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration; // ✅ 응답 대기 시간 상한을 설정하기 위해 Duration을 임포트합니다.


/**
 * 검색된 컨텍스트를 전달해 RAG 백엔드가 제공하는 GPT 엔드포인트를 호출합니다. // ✅ 답변 생성 단계를 별도 모듈로 분리했음을 설명합니다.
 */
@Component
public class RagGptClient implements GptClient {

    private static final Logger log = LoggerFactory.getLogger(RagGptClient.class);

    private final OneAskProperties props;
    private final WebClient ragWebClient;

    public RagGptClient(OneAskProperties props, @Qualifier("ragWebClient") WebClient ragWebClient) {
        this.props = props;
        this.ragWebClient = ragWebClient;
    }

    @Override
    public GptResponse generate(GptRequest request) {
        return generate(request, null);
    }

    @Override
    public GptResponse generate(GptRequest request, Duration timeout) {
        String baseUrl = props.getRag().getBackendUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("RAG 백엔드 URL이 설정되어 있지 않습니다.");
        }

        Mono<GptResponsePayload> call = ragWebClient.post()
                .uri(baseUrl + "/query/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GptResponsePayload.class);

        GptResponsePayload payload;
        try {
            payload = call.block();
        } catch (Exception e) {
            // 여기서 실제 원인 로그 남기기
            log.warn("[GPT][CALL_FAIL] url={}/query/generate err={}", baseUrl, e.toString(), e);
            String rootMsg = (e.getMessage() != null) ? e.getMessage() : e.toString();
            throw new IllegalStateException("GPT 호출 실패: " + rootMsg, e);
        }

        if (payload == null) {
            throw new IllegalStateException("GPT 응답이 비어 있습니다.");
        }
        if (payload.answer() == null || payload.answer().isBlank()) {
            throw new IllegalStateException("GPT 응답 본문이 존재하지 않습니다.");
        }

        return new GptResponse(payload.answer());
    }

    private record GptResponsePayload(String answer) {}
}
