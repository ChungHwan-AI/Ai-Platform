package com.buhmwoo.oneask.modules.document.infrastructure.client;

import com.buhmwoo.oneask.common.config.OneAskProperties; // ✅ RAG 백엔드 위치를 확인하기 위해 구성 프로퍼티를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptClient; // ✅ GPT 호출 계약을 구현하기 위해 인터페이스를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptRequest; // ✅ 질문과 컨텍스트를 담은 요청 모델을 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptResponse; // ✅ 응답 모델을 사용하기 위해 임포트합니다.
import org.springframework.http.MediaType; // ✅ JSON 본문 전송을 위해 MediaType을 임포트합니다.
import org.springframework.stereotype.Component; // ✅ 스프링 빈으로 등록하기 위해 Component 애너테이션을 임포트합니다.
import org.springframework.web.reactive.function.client.WebClient; // ✅ HTTP 호출을 수행하기 위해 WebClient를 임포트합니다.
import reactor.core.publisher.Mono; // ✅ 논블로킹 응답을 처리하기 위해 Mono를 임포트합니다.

import java.time.Duration; // ✅ 응답 대기 시간 상한을 설정하기 위해 Duration을 임포트합니다.

/**
 * 검색된 컨텍스트를 전달해 RAG 백엔드가 제공하는 GPT 엔드포인트를 호출합니다. // ✅ 답변 생성 단계를 별도 모듈로 분리했음을 설명합니다.
 */
@Component // ✅ 자동 주입을 위해 컴포넌트로 선언합니다.
public class RagGptClient implements GptClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15); // ✅ 장시간 대기를 방지하기 위한 타임아웃 값입니다.

    private final OneAskProperties props; // ✅ 백엔드 URL을 주입받기 위한 필드입니다.
    private final WebClient ragWebClient; // ✅ 실제 HTTP 호출을 수행할 WebClient입니다.

    public RagGptClient(OneAskProperties props, WebClient ragWebClient) {
        this.props = props; // ✅ 생성자 주입을 통해 의존성을 명시적으로 표현합니다.
        this.ragWebClient = ragWebClient; // ✅ 동일한 WebClient 빈을 재사용합니다.
    }

    @Override
    public GptResponse generate(GptRequest request) {
        String baseUrl = props.getRag().getBackendUrl(); // ✅ RAG 백엔드 기본 URL을 조회합니다.
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("RAG 백엔드 URL이 설정되어 있지 않습니다."); // ✅ 필수 설정 누락 시 즉시 예외를 발생시킵니다.
        }

        Mono<GptResponsePayload> call = ragWebClient.post()
                .uri(baseUrl + "/query/generate") // ✅ GPT 생성 전용 엔드포인트로 요청을 전송합니다.
                .contentType(MediaType.APPLICATION_JSON) // ✅ JSON 형태로 요청 본문을 보냅니다.
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GptResponsePayload.class); // ✅ 응답을 전용 DTO로 역직렬화합니다.

        GptResponsePayload payload = call.block(REQUEST_TIMEOUT); // ✅ 정해진 시간까지만 기다려 지연을 최소화합니다.
        if (payload == null) {
            throw new IllegalStateException("GPT 응답이 비어 있습니다."); // ✅ 비정상 상황을 명확히 알립니다.
        }
        if (payload.answer() == null || payload.answer().isBlank()) {
            throw new IllegalStateException("GPT 응답 본문이 존재하지 않습니다."); // ✅ 의미 없는 응답을 방지합니다.
        }
        return new GptResponse(payload.answer()); // ✅ 상위 계층에서 동일한 타입을 사용하도록 변환합니다.
    }

    /**
     * /query/generate 응답 구조를 역직렬화하기 위한 내부 레코드입니다. // ✅ WebClient DTO 매핑을 단순화합니다.
     */
    private record GptResponsePayload(String answer) {
    }
}