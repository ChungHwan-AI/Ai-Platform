package com.buhmwoo.oneask.modules.document.infrastructure.client;

import com.buhmwoo.oneask.common.config.OneAskProperties; // ✅ 구성 프로퍼티에서 RAG 백엔드 URL을 읽어오기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalRequest; // ✅ 검색 입력 모델을 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalResult; // ✅ 검색 결과 모델을 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetriever; // ✅ 인터페이스 구현체임을 명시하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.RetrievedDocumentChunk; // ✅ 검색된 청크 정보를 DTO로 변환하기 위해 임포트합니다.
import org.springframework.beans.factory.annotation.Qualifier; // ✅ 특정 이름의 WebClient 빈을 주입하기 위해 Qualifier를 사용합니다.
import org.springframework.http.MediaType; // ✅ JSON 요청을 보내기 위해 MediaType을 임포트합니다.
import org.springframework.stereotype.Component; // ✅ 스프링 빈으로 등록하기 위해 Component 애너테이션을 임포트합니다.
import org.springframework.web.reactive.function.client.WebClient; // ✅ RAG 백엔드 HTTP 호출에 사용할 WebClient를 임포트합니다.
import reactor.core.publisher.Mono; // ✅ WebClient 응답을 안전하게 처리하기 위해 Mono를 임포트합니다.

import java.time.Duration; // ✅ 검색 호출에 대한 최대 대기 시간을 정의하기 위해 Duration을 임포트합니다.

import java.util.List; // ✅ 검색 결과를 리스트 형태로 다루기 위해 임포트합니다.
import java.util.Map; // ✅ 요청 본문 생성을 위해 Map을 임포트합니다.

/**
 * RAG 백엔드의 검색 전용 엔드포인트를 호출하는 구현체입니다. // ✅ 검색 단계 로직을 별도 컴포넌트로 분리했음을 설명합니다.
 */
@Component // ✅ 자동 주입을 위해 컴포넌트로 등록합니다.
public class RagDocumentRetriever implements DocumentRetriever {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15); // ✅ 검색 응답 지연을 줄이기 위한 타임아웃 값입니다.
    private final OneAskProperties props; // ✅ 구성 파일에서 주입한 RAG 백엔드 URL을 보관합니다.
    private final WebClient ragWebClient; // ✅ HTTP 통신을 담당할 WebClient 인스턴스를 보관합니다.

    public RagDocumentRetriever(OneAskProperties props, @Qualifier("ragWebClient") WebClient ragWebClient) {
        this.props = props; // ✅ 생성자 주입을 통해 테스트와 유지보수를 용이하게 합니다.
        this.ragWebClient = ragWebClient; // ✅ 동일한 WebClient 빈을 재사용해 네트워크 설정 일관성을 유지합니다.
    }

    @Override
    public DocumentRetrievalResult retrieve(DocumentRetrievalRequest request) {
        String baseUrl = props.getRag().getBackendUrl(); // ✅ 구성값에서 RAG 백엔드 기본 URL을 조회합니다.
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("RAG 백엔드 URL이 설정되어 있지 않습니다."); // ✅ 필수 설정 누락 시 즉시 실패를 반환합니다.
        }

        Map<String, Object> body = new java.util.HashMap<>(); // ✅ null 값을 허용하기 위해 가변 맵으로 요청 본문을 구성합니다.
        body.put("question", request.question()); // ✅ 사용자 질문을 그대로 전달합니다.
        body.put("docId", request.docId()); // ✅ 선택적 문서 UUID를 전달해 검색 범위를 제한합니다.
        body.put("top_k", request.topK()); // ✅ 검색할 청크 개수를 전달합니다.

        Mono<RetrieveResponsePayload> call = ragWebClient.post()
                .uri(baseUrl + "/query/retrieve") // ✅ 검색 전용 엔드포인트로 요청을 전송합니다.
                .contentType(MediaType.APPLICATION_JSON) // ✅ JSON 본문을 전송함을 명시합니다.
                .bodyValue(body)
                .retrieve()
                .bodyToMono(RetrieveResponsePayload.class); // ✅ 응답을 DTO로 역직렬화합니다.

        RetrieveResponsePayload payload = call.block(REQUEST_TIMEOUT); // ✅ 제한된 시간 동안만 대기해 전체 응답 속도를 개선합니다.
        if (payload == null) {
            throw new IllegalStateException("RAG 검색 응답이 비어 있습니다."); // ✅ 예외 상황을 명시적으로 알립니다.
        }

        List<RetrievedDocumentChunk> chunks = payload.matches().stream()
                .map(match -> new RetrievedDocumentChunk(
                        match.reference(), // ✅ 인용 라벨을 그대로 전달합니다.
                        match.chunkIndex(), // ✅ 결과 순번을 유지합니다.
                        match.content(), // ✅ GPT 프롬프트에 투입할 본문을 제공합니다.
                        match.preview(), // ✅ UI 미리보기 텍스트를 제공합니다.
                        match.source(), // ✅ 출처 식별자를 제공합니다.
                        match.page(), // ✅ 페이지 정보가 있을 경우 포함합니다.
                        match.metadata()
                ))
                .toList();

        return new DocumentRetrievalResult(payload.context(), chunks); // ✅ GPT 호출 단계에서 재사용할 컨텍스트와 청크를 묶어 반환합니다.
    }

    /**
     * /query/retrieve 응답 구조를 역직렬화하기 위한 내부 레코드입니다. // ✅ WebClient 가독성을 높이기 위해 분리했습니다.
     */
    private record RetrieveResponsePayload(String context, List<MatchPayload> matches) {
    }

    /**
     * 검색 결과 청크 정보를 매핑하기 위한 내부 레코드입니다. // ✅ JSON 필드와 자바 필드를 연결합니다.
     */
    private record MatchPayload(String reference, int chunkIndex, String content, String preview,
                                String source, Integer page, Map<String, Object> metadata) {
    }
}