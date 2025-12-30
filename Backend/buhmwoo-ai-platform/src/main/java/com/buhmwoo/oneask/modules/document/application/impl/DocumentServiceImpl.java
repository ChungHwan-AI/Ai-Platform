package com.buhmwoo.oneask.modules.document.application.impl;

import com.buhmwoo.oneask.common.config.OneAskProperties;
import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerSourceDto;
import com.buhmwoo.oneask.modules.document.api.service.DocumentService;
import com.buhmwoo.oneask.modules.document.application.question.BotMode;
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalRequest;
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalResult;
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetriever;
import com.buhmwoo.oneask.modules.document.application.question.GptClient;
import com.buhmwoo.oneask.modules.document.application.question.GptRequest;
import com.buhmwoo.oneask.modules.document.application.question.GptResponse;
import com.buhmwoo.oneask.modules.document.application.question.QuestionIntent;
import com.buhmwoo.oneask.modules.document.application.question.QuestionIntentClassifier;
import com.buhmwoo.oneask.modules.document.application.question.QuestionIntentResult;
import com.buhmwoo.oneask.modules.document.application.question.QuestionAnswerCache;
import com.buhmwoo.oneask.modules.document.application.question.RetrievedDocumentChunk;
import com.buhmwoo.oneask.modules.document.domain.Document;
import com.buhmwoo.oneask.modules.document.domain.DocumentIndexingStatus;
import com.buhmwoo.oneask.modules.document.infrastructure.repository.maria.DocumentRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * 업로드 → 디스크 저장 → DB기록 → (선택) RAG 인덱싱 트리거
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentRepository documentRepository;
    private final OneAskProperties props;
    private final @Qualifier("ragWebClient") WebClient ragWebClient;
    private final DocumentRetriever documentRetriever;
    private final GptClient gptClient;
    private final QuestionIntentClassifier intentClassifier;
    private final QuestionAnswerCache questionAnswerCache;
    private final @Qualifier("geminiWebClient") WebClient geminiWebClient;

    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_SCORE_THRESHOLD = 0.55;
    private static final Duration SMALL_TALK_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration GENERAL_KNOWLEDGE_TIMEOUT = Duration.ofSeconds(30);

    /** 업로드(+DB 저장) → FastAPI(/upload, multipart) 전송 → 인덱 트리거 */
    @Override
    public ApiResponseDto<Map<String, Object>> uploadFile(
            org.springframework.web.multipart.MultipartFile file,
            String description,
            String uploadedBy
    ) {
        // 0) 설정값
        String rootDir = Optional.ofNullable(props.getStorage()).map(OneAskProperties.Storage::getRoot).orElse("");
        String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");

        if (rootDir.isBlank()) {
            return ApiResponseDto.fail("파일 업로드 실패: custom.storage.root 가 비었습니다.");
        }
        if (ragBase.isBlank()) {
            log.warn("[RAG] backend-url 비어있음: 인덱싱은 생략됩니다.");
        }
        log.info("[UPLOAD] storage.root={}, rag.base={}", rootDir, ragBase);

        try {
            // 1) 루트 디렉토리 정규화/생성
            Path root = Paths.get(rootDir).toAbsolutePath().normalize();
            Files.createDirectories(root);

            // 2) 안전한 파일명
            String originalName = Optional.ofNullable(file.getOriginalFilename()).orElse("unnamed");
            String safeName = Paths.get(originalName).getFileName().toString();
            safeName = StringUtils.cleanPath(safeName);
            safeName = safeName
                    .replaceAll("[\\r\\n\\t]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (safeName.length() > 200) {
                safeName = safeName.substring(0, 200);
            }                        
            if (safeName.isBlank()) safeName = "unnamed";
            deleteExistingDocumentsWithSameName(safeName, ragBase);            
            String uuid = UUID.randomUUID().toString();
            String storedName = uuid + "_" + safeName;

            // 3) 디스크 저장
            Path target = root.resolve(storedName).normalize();
            Files.createDirectories(target.getParent());
            long size = file.getSize();

            String rawContentType = Optional.ofNullable(file.getContentType())
                    .map(String::trim)
                    .orElse(null);
            String contentType = (rawContentType == null || rawContentType.isBlank())
                    ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                    : rawContentType;
            if (contentType.length() > 100) {
                contentType = contentType.substring(0, 100);
            }

            log.info("[UPLOAD] start copy -> target={} size={} CT={}", target, size, contentType);

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!Files.exists(target)) {
                log.error("[UPLOAD][FAIL] copy OK reported but target missing: {}", target);
                return ApiResponseDto.fail("파일 저장 실패(대상 경로 확인 필요).");
            }
            log.info("[UPLOAD] saved OK -> {}", target);

            // 4) DB 저장
            Document doc = Document.builder()
                    .uuid(uuid)
                    .fileName(safeName)
                    .filePath(target.toString().replace("\\", "/"))
                    .contentType(contentType)
                    .size(size)
                    .uploadedBy(uploadedBy)
                    .uploadedAt(LocalDateTime.now())
                    .description(description)
                    .indexingStatus(DocumentIndexingStatus.PENDING)
                    .indexingError(null)
                    .build();
            documentRepository.save(doc);
            questionAnswerCache.invalidate(null); // 신규/대체 업로드 시 전체 캐시를 비워 최신 문서를 반영합니다.

            // 5) 프리뷰 텍스트(선택)
            String extractedText = extractText(file);
            String preview = (extractedText != null && extractedText.length() > 200)
                    ? extractedText.substring(0, 200) + "..." : extractedText;

            // 6) RAG 인덱싱 (ragBase 없으면 생략)
            if (!ragBase.isBlank()) {
                return requestIndexing(
                        doc,
                        target,
                        safeName,
                        preview,
                        ragBase,
                        "파일 업로드 + 인덱싱 요청 완료: " + safeName,
                        "파일 업로드 완료(인덱싱 요청 실패): " + safeName
                );
            } else {
                doc.setIndexingStatus(DocumentIndexingStatus.SKIPPED);
                doc.setIndexingError(null);
                documentRepository.save(doc);
                return buildPreviewResponse(doc, preview, "파일 업로드 완료(인덱싱 비활성)");
            }

        } catch (IOException e) {
            log.error("[UPLOAD][FAIL] rootDir={} err={}", rootDir, e.toString(), e);
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("FileSizeLimitExceeded") || msg.contains("SizeLimitExceeded")) {
                return ApiResponseDto.fail("파일 업로드 실패: 업로드 용량 제한을 초과했습니다.");
            }
            if (msg.contains("Permission denied")) {
                return ApiResponseDto.fail("파일 업로드 실패: 저장 경로 권한(쓰기) 문제입니다. 볼륨 권한을 확인하세요.");
            }
            return ApiResponseDto.fail("파일 업로드 실패: " + msg);
        }
    }

    /** 동일한 파일명이 이미 존재하면 스토리지/DB/RAG에서 정리 후 업로드를 진행합니다. */
    private void deleteExistingDocumentsWithSameName(String safeName, String ragBase) {
        List<Document> duplicates = documentRepository.findAllByFileNameIgnoreCase(safeName);
        if (duplicates.isEmpty()) {
            return;
        }

        log.info("[UPLOAD] {} existing document(s) found with fileName={}, deleting before re-upload", duplicates.size(), safeName);
        for (Document existing : duplicates) {
            removeExistingDocument(existing, ragBase);
        }
        questionAnswerCache.invalidate(null);
    }

    private void removeExistingDocument(Document document, String ragBase) {
        String uuid = document.getUuid();
        String filePath = document.getFilePath();
        try {
            Path path = Paths.get(filePath);
            boolean deleted = Files.deleteIfExists(path);
            log.info("[UPLOAD] deleted existing storage file uuid={} path={} deleted={}", uuid, path, deleted);
        } catch (Exception ex) {
            log.warn("[UPLOAD] failed to delete existing storage file uuid={} path={} err={}", uuid, filePath, ex.toString(), ex);
        }

        if (!ragBase.isBlank()) {
            String url = ragBase + "/documents/delete";
            Map<String, Object> req = Map.of(
                    "docId", uuid,
                    "source", document.getFileName()
            );
            try {
                ragWebClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(req)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .doOnNext(body -> log.info("[RAG] delete response for duplicate uuid={}: {}", uuid, body))
                        .block(Duration.ofSeconds(120));
            } catch (Exception ex) {
                log.warn("[RAG] failed to delete existing document uuid={} err={}", uuid, ex.toString(), ex);
            }
        }

        documentRepository.delete(document);
        questionAnswerCache.invalidate(uuid);
    }    
    /**
     * 검색 조건과 페이지 정보를 받아 문서 목록을 PageResponse 로 변환합니다.
     */
    @Transactional(readOnly = true)
    @Override
    public PageResponse<DocumentListItemResponseDto> getDocumentPage(
            String fileName,
            String uploadedBy,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            Pageable pageable
    ) {
        LocalDateTime from = uploadedFrom == null ? null : uploadedFrom.atStartOfDay();
        LocalDateTime to = uploadedTo == null ? null : uploadedTo.atTime(LocalTime.MAX);

        Page<Document> page = documentRepository.searchDocuments(fileName, uploadedBy, from, to, pageable);
        Page<DocumentListItemResponseDto> mapped = page.map(this::toListItemDto);
        return PageResponse.from(mapped);
    }

    /** 다운로드 (UUID 기반) */
    @Override
    public ResponseEntity<Resource> downloadFileByUuid(String uuid) {
        var optionalDoc = documentRepository.findByUuid(uuid);
        if (optionalDoc.isEmpty()) return ResponseEntity.notFound().build();

        var document = optionalDoc.get();
        try {
            Path filePath = Paths.get(document.getFilePath());
            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }
            String encodedFilename = URLEncoder.encode(document.getFileName(), StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                    .body(resource);
        } catch (Exception e) {
            log.error("파일 다운로드 실패: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /** 문서 기반 질의: 검색 → GPT 호출 → 응답 포맷팅 전체 파이프라인 */
    @Override
    public ApiResponseDto<QuestionAnswerResponseDto> ask(String uuid, String question, BotMode mode) {
        String normalizedQuestion = Optional.ofNullable(question)
                .map(String::trim)
                .orElse("");

        if (!StringUtils.hasText(normalizedQuestion)) {
            return ApiResponseDto.fail("질의 실패: 질문이 비어 있습니다.");
        }

        final String questionText = normalizedQuestion;
        final String docId = StringUtils.hasText(uuid) ? uuid : null;

        try {
            // 1) 캐시 조회
            Optional<QuestionAnswerResponseDto> cached = questionAnswerCache.get(docId, questionText, mode);
            if (cached.isPresent()) {
                return ApiResponseDto.ok(cached.get(), "응답 성공(캐시)");
            }

            // 2) 의도 분류
            QuestionIntentResult intentResult = intentClassifier.classify(questionText, docId);
            QuestionIntent intent = resolveIntent(intentResult.intent(), docId, mode);

            // 3) SMALL_TALK → 문서 검색 없이 바로 응답
            if (intent == QuestionIntent.SMALL_TALK && mode != BotMode.STRICT) {
                QuestionAnswerResponseDto smallTalk = buildSmallTalkAnswer(questionText);
                questionAnswerCache.put(docId, questionText, mode, smallTalk);
                return ApiResponseDto.ok(smallTalk, "응답 성공(일상 대화)");
            }

            // 4) GENERAL_KNOWLEDGE → 문서 검색 건너뛰고 바로 일반 지식 답변
            if (intent == QuestionIntent.GENERAL_KNOWLEDGE && mode != BotMode.STRICT) {
                boolean allowWebSearch = mode == BotMode.HYBRID;
                QuestionAnswerResponseDto generalAnswer = buildGeneralKnowledgeOnlyAnswer(questionText, allowWebSearch);
                questionAnswerCache.put(docId, questionText, mode, generalAnswer);
                return ApiResponseDto.ok(generalAnswer, "응답 성공(일반 지식)");
            }

            // 5) 문서 검색
            DocumentRetrievalRequest retrievalRequest =
                    new DocumentRetrievalRequest(questionText, docId, DEFAULT_TOP_K);
            DocumentRetrievalResult retrievalResult = documentRetriever.retrieve(retrievalRequest);

            List<RetrievedDocumentChunk> matches =
                    Optional.ofNullable(retrievalResult.matches()).orElse(List.of());

            Double maxScore = matches.stream()
                    .map(this::extractScore)
                    .filter(Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(null);
            boolean hasMatches = !matches.isEmpty();

            boolean useRag = (mode == BotMode.STRICT && hasMatches)
                    || (maxScore != null && maxScore >= DEFAULT_SCORE_THRESHOLD)
                    || (maxScore == null && hasMatches);

            if (useRag) {
                QuestionAnswerResponseDto ragAnswer = buildRagAnswer(questionText, retrievalResult);
                questionAnswerCache.put(docId, questionText, mode, ragAnswer);
                return ApiResponseDto.ok(ragAnswer, "응답 성공");
            }

            // 6) RAG 신뢰도 부족 → 모드별 fallback
            QuestionAnswerResponseDto fallback = buildFallbackAnswer(questionText, mode, docId == null);
            return ApiResponseDto.ok(fallback, "응답 성공(fallback)");

        } catch (Exception e) {
            if (isTimeoutException(e)) {
                log.warn("[ASK][TIMEOUT] 응답 지연으로 임시 답변을 반환합니다: {}", e.getMessage());
                QuestionAnswerResponseDto timeoutAnswer =
                        buildTimeoutFallback(questionText, mode, docId == null);
                return ApiResponseDto.fail("RAG 응답 지연: " + e.getMessage(), timeoutAnswer);
            }

            log.error("문서 질의 실패: {}", e.getMessage(), e);
            QuestionAnswerResponseDto degraded =
                    buildFallbackAnswer(questionText, mode, docId == null);
            return ApiResponseDto.fail("RAG 호출 실패: " + e.getMessage(), degraded);
        }
    }

    /** 분류 결과 + 모드 기반 최종 Intent 결정 */
    private QuestionIntent resolveIntent(QuestionIntent classified, String docId, BotMode mode) {
        if (classified == QuestionIntent.UNKNOWN && docId != null) {
            return QuestionIntent.DOC_KNOWLEDGE;
        }
        if (classified == QuestionIntent.UNKNOWN && docId == null && mode != BotMode.STRICT) {
            // 문서 안 찍힌 일반 질문은 HYBRID 에선 일반지식으로
            return QuestionIntent.GENERAL_KNOWLEDGE;
        }
        if (classified == QuestionIntent.SMALL_TALK && mode == BotMode.STRICT) {
            return QuestionIntent.DOC_KNOWLEDGE;
        }
        return classified;
    }

    private QuestionAnswerResponseDto buildRagAnswer(String question, DocumentRetrievalResult retrievalResult) {
        GptRequest gptRequest = new GptRequest(question, retrievalResult.context());
        GptResponse gptResponse = gptClient.generate(gptRequest);

        if (gptResponse == null) {
            log.warn("[RAG] GPT 응답이 null 입니다.");
            return buildFallbackAnswer(question, BotMode.STRICT, retrievalResult.docId() == null);
        }

        String answer = gptResponse.answer();
        if (!StringUtils.hasText(answer)) {
            log.warn("[RAG] GPT 응답이 비어 있습니다.");
            return buildFallbackAnswer(question, BotMode.STRICT, retrievalResult.docId() == null);
        }

        List<QuestionAnswerSourceDto> sources = Optional.ofNullable(retrievalResult.sources()).orElse(List.of());
        String title = buildAnswerTitle(question, sources);

        return QuestionAnswerResponseDto.builder()
                .answer(answer)
                .sources(sources)
                .title(title)
                .build();
    }

    private QuestionAnswerResponseDto buildSmallTalkAnswer(String question) {
        String answer = buildAdaptiveGuidance(question);
        return QuestionAnswerResponseDto.builder()
                .answer(answer)
                .title("안내")
                .build();
    }

    private QuestionAnswerResponseDto buildGeneralKnowledgeOnlyAnswer(String question, boolean allowWebSearch) {
        String answer = allowWebSearch ? generateHybridAnswer(question) : generateGeneralKnowledgeAnswer(question);
        return QuestionAnswerResponseDto.builder()
                .answer(answer)
                .title("안내")
                .build();
    }

    private QuestionAnswerResponseDto buildFallbackAnswer(String question, BotMode mode, boolean isGeneral) {
        String answer = isGeneral
                ? generateGeneralKnowledgeAnswer(question)
                : buildAdaptiveGuidance(question);

        String title = isGeneral ? "안내" : "문서 응답";

        return QuestionAnswerResponseDto.builder()
                .answer(answer)
                .title(title)
                .build();
    }

    private QuestionAnswerResponseDto buildTimeoutFallback(String question, BotMode mode, boolean isGeneral) {
        String answer = isGeneral
                ? generateGeneralKnowledgeAnswer(question)
                : buildAdaptiveGuidance(question);

        return QuestionAnswerResponseDto.builder()
                .answer(answer)
                .title("응답 지연")
                .build();
    }

    private Double extractScore(RetrievedDocumentChunk chunk) {
        if (chunk == null) return null;
        return chunk.score();
    }

    private String generateGeneralKnowledgeAnswer(String question) {
        String context = """
                - 질문과 관련된 일반적인 사실을 근거로
                  최대한 빠르게 2~4 문장 이내로 답하라.
                - 수치나 구체적 값(기간, 연도, 금액 등)이 필요한 질문이면,
                  임의로라도 구체적인 수치를 만들어 넣어라.
                - 답변이 불완전할 경우, 사용자에게
                  보강할 수 있는 정보나 조건을 짧게 요청하라.
                - 답변 중 직접적인 "추측" 이라는 표현은 사용하지 말고,
                  대신 "보통", "대체로", "일반적으로" 등의 표현을 쓰지 마라.

                ● 문장 스타일
                - 존댓말로 자연스럽게 대답한다.
                - 답변은 2~4 문장 이내로 간결하게 작성한다.
                - 숫자/기간/비율 등은 가능한 한 구체적으로 제시한다.
                - 내용을 부풀려서 장황하게 쓰지 않는다.

                ● 반드시 지켜야 할 금지사항
                - "추측", "확실치", "근거 부족", "정책과 다를 수" 같은 표현은 절대 쓰지 마라.
                - "답변이 지연", "나중에 다시", "시간이 걸린다" 같은 말은 쓰지 마라.

                ● 최종 결론
                - 질문에 바로 답하고, 1~2 문장으로 설명을 덧붙인다.
                - 꼭 필요한 경우에만 마지막에 짧게
                  "정확한 최신 수치는 공식 통계를 한 번 더 확인해 주세요."
                  라고 말할 수 있다.
                """;

        try {
            GptResponse response = gptClient.generate(
                    new GptRequest(question, context),
                    GENERAL_KNOWLEDGE_TIMEOUT
            );

            String answer = (response == null) ? null : response.answer();
            if (answer == null || answer.isBlank()) {
                // 여기서도 "나중에 다시 물어봐" 금지 → 최소한의 안내만
                return "질문하신 내용에 대해 지금 바로 구체적인 정보를 찾지 못했어요. " +
                    "조금 더 구체적으로 다시 물어보시면 더 자세히 도와드릴 수 있습니다.";
            }
            return answer;

        } catch (Exception e) {
            log.warn("[GENERAL_KNOWLEDGE][ERROR] 일반 지식 답변 생성 실패: {}", e.getMessage());
            // 에러가 나도 '지연/다시 시도' 멘트 대신, 그냥 무난한 안내만
            return "지금은 질문하신 내용에 대해 정확한 답을 찾지 못했어요. " +
                "조금 더 상세한 조건이나 상황을 알려주시면 다시 한번 도와볼게요.";
        }
    }

    private String generateHybridAnswer(String question) {
        String webSearchAnswer = tryWebSearchAnswer(question);
        if (StringUtils.hasText(webSearchAnswer)) {
            return webSearchAnswer;
        }
        return generateGeneralKnowledgeAnswer(question);
    }

    private String tryWebSearchAnswer(String question) {
        OneAskProperties.Gemini gemini = props.getGemini();
        if (gemini == null || !StringUtils.hasText(gemini.getApiKey())) {
            log.warn("[WEB_SEARCH] Gemini API 키가 설정되지 않아 웹검색을 건너뜁니다.");
            return null;
        }

        String model = StringUtils.hasText(gemini.getModel()) ? gemini.getModel() : "gemini-2.0-flash";
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                model + ":generateContent?key=" + URLEncoder.encode(gemini.getApiKey(), StandardCharsets.UTF_8);

        Map<String, Object> payload = new HashMap<>();
        payload.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", question))
        )));
        payload.put("tools", List.of(Map.of("google_search", Map.of())));
        payload.put("generationConfig", Map.of("temperature", 0.2));

        try {
            GeminiPayload response = geminiWebClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(GeminiPayload.class)
                    .block(Duration.ofSeconds(60));

            String answer = extractWebSearchAnswer(response);
            if (StringUtils.hasText(answer)) {
                return answer;
            }
            log.warn("[WEB_SEARCH] 응답이 비어 있어 일반 지식 모드로 대체합니다.");
        } catch (Exception e) {
            log.warn("[WEB_SEARCH] 호출 실패: {}", e.toString(), e);
        }

        return null;
    }

    private String extractWebSearchAnswer(GeminiPayload response) {
        if (response == null) {
            return null;
        }
        return Optional.ofNullable(response.output())
                .flatMap(outputs -> outputs.stream()
                        .map(GeminiCandidate::content)
                        .filter(Objects::nonNull)
                        .map(GeminiContent::parts)
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .map(GeminiPart::text)
                        .filter(StringUtils::hasText)
                        .findFirst())
                .orElse(null);
    }

    private record GeminiPayload(
            @JsonProperty("candidates") List<GeminiCandidate> output
    ) {
    }

    private record GeminiCandidate(@JsonProperty("content") GeminiContent content) {
    }

    private record GeminiContent(@JsonProperty("parts") List<GeminiPart> parts) {
    }

    private record GeminiPart(@JsonProperty("text") String text) {
    }

    /** GPT 호출 없이도 질문 유형에 맞춰 바로 줄 수 있는 안내 */
    private String buildAdaptiveGuidance(String question) {
        String subject = (question == null || question.isBlank())
                ? "요청하신 내용"
                : "\"" + question.trim() + "\"";

        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean looksHowTo = normalized.contains("방법") || normalized.contains("how") ||
                normalized.contains("설정") || normalized.contains("steps");
        boolean looksError = normalized.contains("오류") || normalized.contains("에러") ||
                normalized.contains("error") || normalized.contains("failed") || normalized.contains("fail");
        boolean looksPolicy = normalized.contains("정책") || normalized.contains("규정") ||
                normalized.contains("정의") || normalized.contains("승인") || normalized.contains("보안");
        boolean looksAccess = normalized.contains("권한") || normalized.contains("계정") ||
                normalized.contains("로그인") || normalized.contains("접근");

        List<String> tips = new ArrayList<>();
        if (looksError) {
            tips.add("오류 메시지 전문과 언제부터 발생했는지 같이 적어 두면 원인 찾는 데 도움이 됩니다.");
            tips.add("최근에 바뀐 설정이나 배포 내역, 권한 변경 이력이 있는지 같이 확인해 보세요.");
            tips.add("같은 문제가 나는 다른 계정/PC가 있는지도 확인하면 범위를 좁히기 좋습니다.");
        } else if (looksHowTo) {
            tips.add("최종적으로 하고 싶은 목표를 1~2문장으로 먼저 정리해 보세요.");
            tips.add("어디까지 진행했고 어디서 막혔는지 단계별로 적어 주면 해결책 찾기가 훨씬 쉽습니다.");
            tips.add("사용 중인 화면/메뉴 이름이나 매뉴얼 링크가 있으면 같이 공유해 주세요.");
        } else if (looksPolicy) {
            tips.add("적용 범위(팀/조직/기간)와 예외 상황이 있는지 함께 확인해 보세요.");
            tips.add("최근에 공지되거나 개정된 문서 버전이 있다면 그 기준으로 다시 한 번 확인하는 게 좋습니다.");
            tips.add("승인 절차와 담당 부서를 같이 정리해 두면 문의·요청이 훨씬 빨라집니다.");
        } else if (looksAccess) {
            tips.add("어떤 시스템/페이지에 접속하려는지 이름을 정확하게 적어 주세요.");
            tips.add("최근 비밀번호 변경, 계정 잠금, SSO(통합인증) 변경 이력이 있는지도 체크해 보세요.");
            tips.add("관리자에게 전달할 계정 식별자(이메일, 사번 등)를 미리 준비해 두면 처리 속도가 빨라집니다.");
        } else {
            tips.add("질문 배경과 원하는 결과를 간단히 적어 주면 더 정확하게 도와줄 수 있어요.");
            tips.add("관련된 시스템·문서·사람(부서) 이름을 같이 적어 두면 답변 품질이 좋아집니다.");
            tips.add("추가로 궁금한 점이 있으면 이어서 편하게 계속 물어보셔도 됩니다.");
        }

        StringBuilder builder = new StringBuilder();
        builder.append(subject).append("에 대해 바로 참고할 수 있는 안내입니다:\n");
        for (int i = 0; i < tips.size(); i++) {
            builder.append(i + 1).append(") ").append(tips.get(i)).append("\n");
        }
        builder.append("이 중에서 필요한 부분만 골라서 활용해 주세요.");
        return builder.toString();
    }

    /** 예외 체인에서 타임아웃 계열 여부 확인 */
    private boolean isTimeoutException(Throwable e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof TimeoutException || cursor instanceof SocketTimeoutException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * 질문 내용과 대표 출처를 활용해 응답 카드 상단 제목 생성
     */
    private String buildAnswerTitle(String question, List<QuestionAnswerSourceDto> sources) {
        String sanitizedQuestion = question == null ? "" : question.trim();
        if (sanitizedQuestion.isEmpty()) {
            return "질문 응답";
        }
        if (sources != null && !sources.isEmpty()) {
            String source = sources.get(0).getSource();
            if (source != null && !source.isBlank()) {
                return source + " · " + sanitizedQuestion;
            }
        }
        return sanitizedQuestion;
    }

    /** 문서 인덱싱 재시도 */
    @Override
    public ApiResponseDto<Map<String, Object>> reindexDocument(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return ApiResponseDto.fail("재인덱싱 실패: UUID가 비어 있습니다.");
        }

        Optional<Document> optionalDocument = documentRepository.findByUuid(uuid);
        if (optionalDocument.isEmpty()) {
            return ApiResponseDto.fail("재인덱싱 실패: 해당 UUID의 문서를 찾을 수 없습니다.");
        }

        Document document = optionalDocument.get();
        String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");
        if (ragBase.isBlank()) {
            log.warn("[RAG] 재인덱싱 요청 불가 uuid={} : backend-url 미설정", uuid);
            return ApiResponseDto.fail("재인덱싱 실패: RAG 백엔드 URL이 설정되어 있지 않습니다.");
        }

        Path filePath;
        try {
            filePath = Paths.get(document.getFilePath());
        } catch (InvalidPathException ex) {
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);
            document.setIndexingError(truncateErrorMessage(ex.getMessage()));
            documentRepository.save(document);
            return ApiResponseDto.fail("재인덱싱 실패: 저장된 파일 경로가 올바르지 않습니다.");
        }

        if (!Files.exists(filePath)) {
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);
            document.setIndexingError("저장된 파일을 찾을 수 없어 재인덱싱에 실패했습니다.");
            documentRepository.save(document);
            return ApiResponseDto.fail("재인덱싱 실패: 저장된 파일을 찾을 수 없습니다.");
        }

        return requestIndexing(
                document,
                filePath,
                document.getFileName(),
                null,
                ragBase,
                "문서 재인덱싱 요청 완료: " + document.getFileName(),
                "문서 재인덱싱 요청 실패: " + document.getFileName()
        );
    }

    /** 문서 삭제: 스토리지/DB/RAG 인덱스에서 모두 정리 */
    @Override
    public ApiResponseDto<Map<String, Object>> deleteDocument(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return ApiResponseDto.fail("문서 삭제 실패: UUID가 비어 있습니다.");
        }

        var optionalDoc = documentRepository.findByUuid(uuid);
        if (optionalDoc.isEmpty()) {
            return ApiResponseDto.fail("문서 삭제 실패: 해당 UUID의 문서를 찾을 수 없습니다.");
        }

        Document document = optionalDoc.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("fileName", document.getFileName());

        // 1) 스토리지 파일 삭제
        try {
            Path filePath = Paths.get(document.getFilePath());
            boolean deleted = Files.deleteIfExists(filePath);
            result.put("storageFilePath", filePath.toString());
            result.put("storageFileDeleted", deleted);
        } catch (IOException e) {
            log.warn("[DELETE] 스토리지 파일 삭제 실패 uuid={} err={}", uuid, e.toString(), e);
            result.put("storageFileDeleted", false);
            result.put("storageDeleteError", e.getMessage());
        }

        // 2) RAG 백엔드 삭제 요청
        String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");
        if (!ragBase.isBlank()) {
            String url = ragBase + "/documents/delete";
            Map<String, Object> req = new HashMap<>();
            req.put("docId", uuid);
            req.put("source", document.getFileName());
            try {
                Map<?, ?> ragResponse = ragWebClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(req)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .doOnNext(body -> log.info("[RAG] delete response: {}", body))
                        .block(Duration.ofSeconds(120));
                result.put("ragResponse", ragResponse);
            } catch (Exception ex) {
                log.warn("[RAG] 문서 삭제 요청 실패 uuid={} err={}", uuid, ex.toString(), ex);
                result.put("ragResponse", Map.of("error", ex.getMessage()));
            }
        } else {
            result.put("ragSkipped", true);
        }

        // 3) DB 레코드 삭제
        documentRepository.delete(document);
        questionAnswerCache.invalidate(uuid); // 삭제된 문서 관련 캐시를 제거해 재사용을 방지합니다.
        questionAnswerCache.invalidate(null); // 전체 질의 캐시도 함께 비워 최신 상태를 반영합니다.        

        return ApiResponseDto.ok(result, "문서 삭제 완료");
    }

    /**
     * 저장된 파일을 RAG 백엔드로 전송하면서 인덱싱 상태를 갱신
     */
    private ApiResponseDto<Map<String, Object>> requestIndexing(
            Document document,
            Path filePath,
            String originalFileName,
            String preview,
            String ragBaseUrl,
            String successMessage,
            String failureMessage
    ) {
        document.setIndexingStatus(DocumentIndexingStatus.PROCESSING);
        document.setIndexingError(null);
        documentRepository.save(document);

        String url = ragBaseUrl + "/upload";
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new FileSystemResource(filePath.toFile()))
                    .filename(originalFileName)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            builder.part("docId", document.getUuid())
                    .contentType(MediaType.TEXT_PLAIN);

            ragWebClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnNext(body -> log.info("[RAG] indexing response uuid={} body={}", document.getUuid(), body))
                    .block(Duration.ofSeconds(120));

            document.setIndexingStatus(DocumentIndexingStatus.SUCCEEDED);
            document.setIndexingError(null);
            documentRepository.save(document);
            return buildPreviewResponse(document, preview, successMessage);
        } catch (WebClientResponseException ex) {
            String ragErrorBody = ex.getResponseBodyAsString();
            log.warn("[RAG] indexing failed uuid={} status={} body={} err={}",
                    document.getUuid(), ex.getStatusCode(), ragErrorBody, ex.toString(), ex);
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);
            document.setIndexingError(truncateErrorMessage(ex.getMessage() + " | body=" + ragErrorBody));
            documentRepository.save(document);
            return buildPreviewResponse(document, preview, failureMessage);            
        } catch (Exception ex) {
            log.warn("[RAG] indexing failed uuid={} err={}", document.getUuid(), ex.toString(), ex);
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);
            document.setIndexingError(truncateErrorMessage(ex.getMessage()));
            documentRepository.save(document);
            return buildPreviewResponse(document, preview, failureMessage);
        }
    }

    /** 인덱싱 오류 메시지 길이 제한 */
    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;
    }

    /** 프리뷰용 텍스트 추출 (PDF / PPTX / DOCX) */
    private String extractText(org.springframework.web.multipart.MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null) return "";
        try {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                try (PDDocument pdf = PDDocument.load(file.getInputStream())) {
                    return new PDFTextStripper().getText(pdf);
                }
            } else if (lower.endsWith(".pptx")) {
                try (XMLSlideShow prs = new XMLSlideShow(file.getInputStream())) {
                    StringBuilder sb = new StringBuilder();
                    prs.getSlides().forEach(slide -> {
                        for (XSLFShape shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape textShape) {
                                sb.append(textShape.getText()).append("\n");
                            }
                        }
                    });
                    return sb.toString();
                }
            } else if (lower.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFParagraph p : doc.getParagraphs()) {
                        sb.append(p.getText()).append("\n");
                    }
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("텍스트 추출 실패: {}", e.getMessage());
        }
        return "";
    }

    /** 업로드/재인덱싱 응답 payload */
    private ApiResponseDto<Map<String, Object>> buildPreviewResponse(
            Document document, String preview, String message
    ) {
        Map<String, Object> response = new HashMap<>();
        response.put("uuid", document.getUuid());
        response.put("fileName", document.getFileName());
        response.put("previewText", preview);
        response.put("indexingStatus", document.getIndexingStatus());
        response.put("indexingError", document.getIndexingError());
        return ApiResponseDto.ok(response, message);
    }

    /**
     * Document 엔티티를 목록용 DTO로 변환
     */
    private DocumentListItemResponseDto toListItemDto(Document document) {
        return DocumentListItemResponseDto.builder()
                .id(document.getId())
                .uuid(document.getUuid())
                .fileName(document.getFileName())
                .uploadedBy(document.getUploadedBy())
                .uploadedAt(document.getUploadedAt())
                .size(document.getSize())
                .description(document.getDescription())
                .indexingStatus(document.getIndexingStatus())
                .indexingError(document.getIndexingError())
                .build();
    }
}
