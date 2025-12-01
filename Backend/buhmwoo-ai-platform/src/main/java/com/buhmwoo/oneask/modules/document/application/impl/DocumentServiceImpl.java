package com.buhmwoo.oneask.modules.document.application.impl;

import com.buhmwoo.oneask.common.config.OneAskProperties;
import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerResponseDto; // ✅ 통일된 질문 응답 포맷을 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerSourceDto; // ✅ 검색된 출처 정보를 DTO로 변환하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.api.service.DocumentService;
import com.buhmwoo.oneask.modules.document.application.question.BotMode; // ✅ fallback 정책을 전환하기 위해 봇 모드를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalRequest; // ✅ 검색 단계 호출을 위해 요청 DTO를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetrievalResult; // ✅ 검색 결과 DTO를 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.DocumentRetriever; // ✅ 검색 모듈 인터페이스를 주입받기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptClient; // ✅ GPT 호출 모듈을 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptRequest; // ✅ GPT 요청 DTO를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.GptResponse; // ✅ GPT 응답 DTO를 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.QuestionAnswerCache; // ✅ 질문 응답 캐시 컴포넌트를 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.RetrievedDocumentChunk; // ✅ 검색 결과에서 점수를 추출하기 위해 청크 모델을 임포트합니다.
import com.buhmwoo.oneask.modules.document.domain.Document;
import com.buhmwoo.oneask.modules.document.domain.DocumentIndexingStatus;
import com.buhmwoo.oneask.modules.document.infrastructure.repository.maria.DocumentRepository;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;


import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.net.SocketTimeoutException; // ✅ 네트워크 타임아웃 예외를 감지하기 위해 추가 임포트합니다.
import java.util.concurrent.TimeoutException; // ✅ Reactor 블록 대기 초과 상황을 식별하기 위해 추가 임포트합니다.

/**
 * 업로드 → 디스크 저장 → DB기록 → (선택) RAG 인덱싱 트리거
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService { // ✅ 공통 서비스 인터페이스를 구현하도록 명시합니다.

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentRepository documentRepository; // ✅ 문서 메타데이터를 관리하는 JPA 저장소입니다.
    private final OneAskProperties props; // ✅ 스토리지 및 RAG 백엔드 설정을 보관합니다.
    private final WebClient ragWebClient; // ✅ RAG 백엔드와 통신할 공용 WebClient입니다.
    private final DocumentRetriever documentRetriever; // ✅ 검색 단계를 담당하는 모듈입니다.
    private final GptClient gptClient; // ✅ GPT 응답 생성을 담당하는 모듈입니다.
    private final QuestionAnswerCache questionAnswerCache; // ✅ 반복 질문에 대한 캐시를 제공합니다.

    private static final int DEFAULT_TOP_K = 4; // ✅ 검색 단계에서 기본으로 가져올 청크 개수를 정의합니다.
    private static final double DEFAULT_SCORE_THRESHOLD = 0.55; // ✅ 충분한 유사도가 확보된 경우에만 문서 기반 답변을 신뢰하도록 임계값을 상향 조정합니다.
    private static final Duration SMALL_TALK_TIMEOUT = Duration.ofSeconds(8); // ✅ 일상 대화는 짧은 대기 시간 내에 응답하도록 제한해 체감 속도를 높입니다.
    private static final Duration GENERAL_KNOWLEDGE_TIMEOUT = Duration.ofSeconds(12); // ✅ 일반 지식 fallback도 과도한 대기 없이 빠르게 답변하도록 합니다.

    /** 업로드(+DB 저장) → FastAPI(/upload, multipart) 전송 → 인덱싱 트리거 */
    @Override // ✅ 인터페이스 계약을 충실히 따르고 있음을 표시합니다.
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
            if (safeName.isBlank()) safeName = "unnamed";
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
                    .indexingStatus(DocumentIndexingStatus.PENDING)   // ✅ 업로드 직후 인덱싱을 아직 수행하지 않았음을 표시합니다.
                    .indexingError(null)   // ✅ 최초 업로드 시에는 에러 메시지가 없도록 초기화합니다.                    
                    .build();
            documentRepository.save(doc);

            // 5) 프리뷰 텍스트(선택)
            String extractedText = extractText(file);
            String preview = (extractedText != null && extractedText.length() > 200)
                    ? extractedText.substring(0, 200) + "..." : extractedText;

            // 6) RAG 인덱싱 (ragBase 없으면 생략)
            if (!ragBase.isBlank()) {
                return requestIndexing(doc, target, safeName, preview, ragBase,
                        "파일 업로드 + 인덱싱 요청 완료: " + safeName,
                        "파일 업로드 완료(인덱싱 요청 실패): " + safeName);                
            } else {
                doc.setIndexingStatus(DocumentIndexingStatus.SKIPPED);   // ✅ RAG 백엔드가 비활성화된 경우 상태를 명확히 기록합니다.
                doc.setIndexingError(null);   // ✅ 인덱싱을 수행하지 않았으므로 오류 메시지를 초기화합니다.
                documentRepository.save(doc);   // ✅ 상태 변경 내용을 즉시 반영합니다.
                return buildPreviewResponse(doc, preview,
                        "파일 업로드 완료(인덱싱 비활성)");
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

    /**
     * 검색 조건과 페이지 정보를 받아 문서 목록을 PageResponse 로 변환합니다.
     */
    @Transactional(readOnly = true)
    @Override // ✅ 페이지 조회 로직이 인터페이스 시그니처를 구현함을 명시합니다.
    public PageResponse<DocumentListItemResponseDto> getDocumentPage(
            String fileName,   // ✅ 파일명 검색어
            String uploadedBy,   // ✅ 업로더 검색어
            LocalDate uploadedFrom,   // ✅ 업로드 시작일(로컬 날짜)
            LocalDate uploadedTo,   // ✅ 업로드 종료일(로컬 날짜)
            Pageable pageable   // ✅ 페이징/정렬 정보
    ) {
        LocalDateTime from = uploadedFrom == null ? null : uploadedFrom.atStartOfDay();   // ✅ 검색 시작일을 00:00으로 변환
        LocalDateTime to = uploadedTo == null ? null : uploadedTo.atTime(LocalTime.MAX);   // ✅ 검색 종료일을 23:59:59.999로 변환

        Page<Document> page = documentRepository.searchDocuments(fileName, uploadedBy, from, to, pageable);   // ✅ 저장소에서 조건에 맞는 문서 조회
        Page<DocumentListItemResponseDto> mapped = page.map(this::toListItemDto);   // ✅ 엔티티를 목록 DTO로 변환
        return PageResponse.from(mapped);   // ✅ PageResponse 형태로 변환하여 반환
    }

    /** 다운로드 (UUID 기반) */
    @Override // ✅ 다운로드 기능이 인터페이스 계약에 속함을 보여줍니다.
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
    @Override // ✅ 질의 처리 로직이 인터페이스 계약을 따른다는 것을 나타냅니다.
    public ApiResponseDto<QuestionAnswerResponseDto> ask(String uuid, String question, BotMode mode) {
        String normalizedQuestion = Optional.ofNullable(question)
                .map(String::trim)
                .orElse(""); // ✅ 질문 앞뒤 공백을 제거해 순수 질의만 남깁니다.

        if (!StringUtils.hasText(normalizedQuestion)) {        
            return ApiResponseDto.fail("질의 실패: 질문이 비어 있습니다."); // ✅ 필수 파라미터 누락을 즉시 안내합니다.
        }
        
        String questionText = normalizedQuestion; // ✅ 정규화된 질문 문자열을 이후 파이프라인 전체에서 일관되게 사용합니다.
        String docId = StringUtils.hasText(uuid) ? uuid : null; // ✅ 문서 ID가 비어 있으면 전체 검색으로 전환합니다.
        Optional<QuestionAnswerResponseDto> cached = questionAnswerCache.get(docId, questionText, mode); // ✅ 동일 질의 및 모드 조합에 대한 캐시를 확인합니다.
        if (cached.isPresent()) {
            return ApiResponseDto.ok(cached.get(), "응답 성공(캐시)"); // ✅ 캐시 적중 시 즉시 반환합니다.
        }

        boolean smallTalkOnly = docId == null && mode != BotMode.STRICT && isGeneralSmallTalk(questionText); // ✅ 전체 질의이면서 확실한 일상 대화일 때만 RAG를 우회합니다.
        if (smallTalkOnly) {
            QuestionAnswerResponseDto smallTalk = buildSmallTalkAnswer(questionText); // ✅ 빠른 응답을 위해 사전 정의된 답변을 생성합니다.
            questionAnswerCache.put(docId, questionText, mode, smallTalk); // ✅ 동일한 일상 질문 재호출 시 즉시 반환하도록 캐싱합니다.
            return ApiResponseDto.ok(smallTalk, "응답 성공(일상 질문)"); // ✅ RAG 호출을 생략한 빠른 응답임을 설명합니다.
        }

        try {    

            DocumentRetrievalRequest retrievalRequest = new DocumentRetrievalRequest(questionText, docId, DEFAULT_TOP_K); // ✅ 기본 top-k 값을 상수로 관리합니다.
            DocumentRetrievalResult retrievalResult = documentRetriever.retrieve(retrievalRequest); // ✅ 검색 단계 실행 결과를 가져옵니다.

            List<RetrievedDocumentChunk> matches = Optional.ofNullable(retrievalResult.matches()).orElse(List.of()); // ✅ 검색 결과가 비어 있을 때 NPE를 방지합니다.
            Double maxScore = matches.stream()
                    .map(this::extractScore) // ✅ 검색 결과의 유사도 점수를 추출합니다.
                    .filter(Objects::nonNull)
                    .max(Double::compareTo)
                    .orElse(null); // ✅ 점수가 없으면 null 로 처리해 fallback 분기에 전달합니다.
            boolean hasMatches = !matches.isEmpty(); // ✅ 스코어 없이도 검색 결과가 있는지 확인합니다.

            boolean useRag = (mode == BotMode.STRICT && hasMatches)
                    || (maxScore != null && maxScore >= DEFAULT_SCORE_THRESHOLD)
                    || (maxScore == null && hasMatches); // ✅ STRICT 모드에서는 점수에 관계없이 검색 결과가 있으면 RAG 흐름을 강제합니다.

            if (useRag) { // ✅ 우선 조건을 변수로 분리해 가독성을 높입니다.
                QuestionAnswerResponseDto ragAnswer = buildRagAnswer(questionText, retrievalResult); // ✅ 정상 RAG 응답을 생성합니다.
                questionAnswerCache.put(docId, questionText, mode, ragAnswer); // ✅ 동일 질의/모드 재호출을 위한 캐시를 저장합니다.
                return ApiResponseDto.ok(ragAnswer, "응답 성공");        
            }
            
            QuestionAnswerResponseDto fallback = buildFallbackAnswer(questionText, mode, docId == null); // ✅ 점수가 부족할 때 모드별 fallback 응답을 생성합니다.
            return ApiResponseDto.ok(fallback, "응답 성공(fallback)");

        } catch (Exception e) {
            if (isTimeoutException(e)) { // ✅ 타임아웃 시에는 실패 상태로 안내 메시지와 임시 답변을 제공합니다.
                log.warn("[ASK][TIMEOUT] 응답 지연으로 임시 답변을 반환합니다: {}", e.getMessage()); // ✅ 운영 로그에 타임아웃 사실을 기록합니다.
                QuestionAnswerResponseDto timeoutAnswer = buildTimeoutFallback(questionText, mode, docId == null); // ✅ 지연 상황을 알려주는 안내 문구와 대체 답변을 구성합니다.
                return ApiResponseDto.fail("RAG 응답 지연: " + e.getMessage(), timeoutAnswer); // ✅ 실패로 표시해 모니터링에서 지연을 인지할 수 있게 합니다.
            }

            log.error("문서 질의 실패: {}", e.getMessage(), e); // ✅ 예외 스택을 함께 남겨 추적 가능성을 높입니다.
            // ✅ RAG 백엔드 장애 시에도 화면이 멈추지 않도록 즉시 fallback 답변을 제공합니다.
            QuestionAnswerResponseDto degraded = buildFallbackAnswer(questionText, mode, docId == null);
            return ApiResponseDto.fail("RAG 호출 실패: " + e.getMessage(), degraded); // ✅ 장애를 성공으로 오인하지 않도록 명확히 실패 응답을 반환합니다.
        }
    }

    private QuestionAnswerResponseDto buildRagAnswer(String question, DocumentRetrievalResult retrievalResult) {
        GptRequest gptRequest = new GptRequest(question, retrievalResult.context()); // ✅ 검색 결과 컨텍스트와 질문을 묶어 LLM 호출 파라미터로 준비합니다.
        GptResponse gptResponse = gptClient.generate(gptRequest); // ✅ GPT 모듈을 통해 최종 답변 생성을 요청합니다.

        if (gptResponse.answer() == null || gptResponse.answer().isBlank()) {
            throw new IllegalStateException("질의 실패: GPT 응답이 비어 있습니다."); // ✅ 의미 있는 답변이 없는 경우 실패로 간주합니다.
        }
        List<QuestionAnswerSourceDto> sources = retrievalResult.matches().stream()
                .map(match -> QuestionAnswerSourceDto.builder()
                        .reference(match.reference())
                        .source(match.source())
                        .page(match.page())
                        .preview(match.preview())
                        .build())
                .toList(); // ✅ 검색된 청크를 앱에서 활용할 수 있는 출처 DTO 목록으로 변환합니다.

        QuestionAnswerResponseDto payload = QuestionAnswerResponseDto.builder()
                .title(buildAnswerTitle(question, sources))
                .answer(gptResponse.answer())
                .sources(sources)
                .fromCache(false)
                .build(); // ✅ 앱에 필요한 응답 본문과 출처 정보를 모두 포함합니다.

        return payload;
    }

    private QuestionAnswerResponseDto buildFallbackAnswer(String question, BotMode mode, boolean globalQuery) {
        if (mode == BotMode.STRICT) { // ✅ STRICT 모드에서는 문서가 없음을 알리고 종료합니다.
            return QuestionAnswerResponseDto.builder()
                    .title(buildAnswerTitle(question, List.of()))
                    .answer("현재 업로드된 문서/DB에서는 이 질문과 관련된 정보를 찾지 못했습니다.\n문서나 데이터가 등록된 후 다시 문의해 주세요.")
                    .sources(List.of())
                    .fromCache(false)
                    .build();
        }

        if (globalQuery && isCasualEverydayQuestion(question)) { // ✅ 전체 질의이면서 일상 질문이면 ChatGPT 스타일로 바로 응답합니다.
            return buildSmallTalkAnswer(question);
        }

        String generalAnswer = generateGeneralKnowledgeAnswer(question); // ✅ HYBRID 모드에서 일반 지식 기반 답변을 준비합니다.
        String hybridMessage = "[문서/DB 검색 결과]\n" +
                "- 현재 보유한 문서/DB에서는 관련 정보를 찾지 못했습니다.\n\n" +
                "[일반적인 지식 기준 답변]\n" +
                generalAnswer + "\n\n" +
                "※ 위 내용은 일반적인 관점에서의 설명이며, 우리 회사의 실제 정책/규정과 다를 수 있습니다. 중요한 사항은 담당자에게 한 번 더 확인해 주세요.";

        return QuestionAnswerResponseDto.builder()
                .title(buildAnswerTitle(question, List.of()))
                .answer(hybridMessage)
                .sources(List.of())
                .fromCache(false)
                .build();
    }

    private QuestionAnswerResponseDto buildTimeoutFallback(String question, BotMode mode, boolean globalQuery) { // ✅ RAG 백엔드 지연 시 사용자에게 전달할 임시 답변을 생성합니다.
        String guidance = "현재 답변이 지연되고 있어요. 잠시 후 다시 시도해 주세요."; // ✅ 지연 상황을 즉시 안내합니다.
        if (mode == BotMode.STRICT) { // ✅ STRICT 모드에서는 문서 검색 실패 메시지와 함께 지연 안내를 제공합니다.
            return QuestionAnswerResponseDto.builder()
                    .title(buildAnswerTitle(question, List.of()))
                    .answer(guidance + "\n지금은 문서 검색이 원활하지 않아 임시 안내만 드려요.")
                    .sources(List.of())
                    .fromCache(false)
                    .build();
        }

        if (globalQuery && isCasualEverydayQuestion(question)) { // ✅ 일상 질문이면 지연 안내 후 바로 자연스러운 답변을 제공합니다.
            QuestionAnswerResponseDto smallTalk = buildSmallTalkAnswer(question);
            return QuestionAnswerResponseDto.builder()
                    .title(smallTalk.getTitle())
                    .answer(guidance + "\n\n" + smallTalk.getAnswer())
                    .sources(List.of())
                    .fromCache(false)
                    .build();
        }

        String generalAnswer = generateGeneralKnowledgeAnswer(question); // ✅ HYBRID 모드에서는 일반 지식 기반 임시 답변을 함께 제공합니다.
        String combined = guidance + "\n\n[임시 답변] " + generalAnswer; // ✅ 안내 문구와 임시 답변을 묶어 전달합니다.

        return QuestionAnswerResponseDto.builder()
                .title(buildAnswerTitle(question, List.of()))
                .answer(combined)
                .sources(List.of())
                .fromCache(false)
                .build();
    }

    private QuestionAnswerResponseDto buildSmallTalkAnswer(String question) { // ✅ 일상 대화형 질문에 즉시 응답하기 위한 헬퍼입니다.
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT); // ✅ 키워드 매칭을 위해 소문자로 변환합니다.

        String baseContext = "(일상 대화) 친근하고 자연스러운 ChatGPT 스타일로 2~3문장 이내로 답변하세요. 질문 언어(한국어/영어)를 따라가고, 맥락을 추론해 추가로 궁금해할만한 정보나 짧은 팁을 덧붙이세요. 불필요한 사과보다는 명확하고 위트 있는 표현을 선택합니다."; // ✅ 전반적인 일상 대화 품질을 ChatGPT 수준으로 끌어올리기 위한 기본 컨텍스트입니다.
        if (normalized.contains("날씨")) { // ✅ 날씨 질문일 때는 실시간처럼 느껴지도록 맥락형 안내를 강화합니다.
            baseContext += " 실시간 관측 데이터에는 직접 접근하지 못하지만, 질문에 포함된 위치·날짜·시간 단서를 활용해 예상 가능한 기온 흐름·강수 가능성·준비물 팁(우산, 겉옷 등)을 간단히 제시하고, 마지막에 최신 정보 확인이 필요하다는 점을 짧게 고지합니다."; // ✅ 실시간 한계를 최소한으로 언급하면서도 실질적인 조언을 함께 제공합니다.
        }

        String message;
        try {
            GptResponse response = gptClient.generate(new GptRequest(question, baseContext), SMALL_TALK_TIMEOUT); // ✅ 짧은 타임아웃으로 호출해 체감 지연 없이 답변을 받습니다.
            message = Optional.ofNullable(response) // ✅ GPT 응답 객체가 null 인 상황까지 방어합니다.
                    .map(GptResponse::answer)
                    .filter(answer -> !answer.isBlank())
                    .orElse("지금은 즉시 답변을 만들지 못했어요. 다시 한번 물어봐 주실래요?"); // ✅ GPT가 응답하지 못한 경우 대비용 안내 문구입니다.
        } catch (Exception e) {
            log.warn("[SMALL_TALK][TIMEOUT] 빠른 응답 실패: {}", e.getMessage()); // ✅ 타임아웃 등 예외 상황을 로그로 남겨 원인 추적을 돕습니다.
            String fallback = generateGeneralKnowledgeAnswer(question); // ✅ 빠른 응답이 실패해도 일반 지식 기반의 즉시 답변을 준비합니다.
            message = "답변이 조금 지연되고 있어요. 잠시 후 다시 물어봐 주시면 더 빠르게 도와드릴게요.\n\n[임시 답변] "
                    + fallback; // ✅ 안내 문구와 함께 대체 답변을 제공해 무조건 응답을 보장합니다.
        }

        return QuestionAnswerResponseDto.builder()
                .title(buildAnswerTitle(question, List.of())) // ✅ 기존 제목 생성 규칙을 재사용합니다.
                .answer(message) // ✅ 준비한 메시지를 본문에 담습니다.
                .sources(List.of()) // ✅ 문서 기반이 아니므로 출처는 비워둡니다.
                .fromCache(false) // ✅ 캐시 여부는 호출부에서 처리하도록 기본값을 둡니다.
                .build();
    }

    private boolean isGeneralSmallTalk(String question) { // ✅ RAG 없이 처리할 수 있는 일상 질문 여부를 판별합니다.
        if (question == null) {
            return false; // ✅ 질문이 없으면 일상 질문으로 보지 않습니다.
        }
        String normalized = question.toLowerCase(Locale.ROOT); // ✅ 키워드 매칭을 위해 소문자로 정규화합니다.
        return normalized.contains("날씨")
                || normalized.contains("안녕")
                || normalized.contains("hello")
                || normalized.contains("hi")
                || normalized.contains("고마워")
                || normalized.contains("thank"); // ✅ 대표적인 일상 키워드를 나열해 빠른 분기를 만듭니다.
    }
        
    private boolean isCasualEverydayQuestion(String question) { // ✅ 하이브리드 모드에서 자유롭게 답할 수 있는 일상 질문을 판별합니다.
        if (question == null || question.isBlank()) {
            return false;
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> corporateKeywords = List.of("정책", "규정", "지침", "절차", "프로세스", "보안", "승인", "결재", "비용", "경비", "인사", "휴가", "근태", "매뉴얼");
        boolean looksCorporate = corporateKeywords.stream().anyMatch(normalized::contains); // ✅ 사내 정책성 질문은 제외합니다.
        if (looksCorporate) {
            return false;
        }

        return isGeneralSmallTalk(question)
                || normalized.contains("인구")
                || normalized.contains("맛집")
                || normalized.contains("여행")
                || normalized.contains("추천")
                || normalized.contains("정보"); // ✅ 일상적인 호기심/대화 소재를 넓혀 자연스러운 답변을 허용합니다.
    }

    private Double extractScore(RetrievedDocumentChunk chunk) {
        Map<String, Object> metadata = chunk.metadata(); // ✅ 검색 결과 메타데이터에서 점수를 찾아봅니다.
        if (metadata == null) {
            return null; // ✅ 점수가 없으면 null 로 반환해 fallback 판단에서 제외합니다.
        }

        Object scoreRaw = metadata.get("score"); // ✅ RAG 백엔드가 score 를 직접 내려줄 때 사용합니다.
        if (scoreRaw instanceof Number number) {
            return number.doubleValue();
        }
        if (scoreRaw instanceof String scoreText) {
            try {
                return Double.parseDouble(scoreText); // ✅ 문자열로 온 점수는 안전하게 변환합니다.
            } catch (NumberFormatException ignored) {
                // ✅ 변환 실패 시 다른 메타데이터를 확인합니다.
            }
        }

        Object distanceRaw = metadata.get("distance"); // ✅ 거리 기반 응답일 경우 score 로 역변환합니다.
        if (distanceRaw instanceof Number number) {
            double distance = number.doubleValue();
            return 1.0 / (1.0 + distance); // ✅ 0~1 범위의 근사 점수로 변환합니다.
        }
        return null; // ✅ 점수를 계산할 수 없는 경우 null 로 처리합니다.
    }

    private String generateGeneralKnowledgeAnswer(String question) {
        String fallbackContext = "(문서/DB 검색 결과 없음) 일반적인 상식과 업계 지식을 바탕으로 질문에 답변해 주세요. " +
                "회사 내부 정책이라고 단정하지 말고 '일반적으로'라는 표현을 사용해 주세요."; // ✅ 일반 지식 기반 답변임을 LLM에 명확히 전달하는 컨텍스트입니다.

        try {
            GptResponse response = gptClient.generate(new GptRequest(question, fallbackContext), GENERAL_KNOWLEDGE_TIMEOUT); // ✅ 과도한 대기 없이 일반 지식 답변을 생성합니다.
            if (response.answer() == null || response.answer().isBlank()) {
                return "일반 지식 기반 답변을 생성하지 못했습니다."; // ✅ 예외 상황에서 사용자에게 안전한 문구를 제공합니다.
            }
            return response.answer();
        } catch (Exception e) {
            log.warn("[GENERAL_KNOWLEDGE][TIMEOUT] fallback 지식 생성 실패: {}", e.getMessage()); // ✅ 대기 초과나 기타 예외를 기록해 진단을 돕습니다.
            return "지금은 일반 지식 답변을 준비하는 데 시간이 너무 오래 걸리고 있어요. 잠시 후 다시 시도해 주세요."; // ✅ 실패 시에도 즉시 안내 메시지를 반환합니다.
        }
    }

    private boolean isTimeoutException(Throwable e) { // ✅ 예외 체인에서 타임아웃 계열 오류를 탐지하기 위한 헬퍼입니다.
        Throwable cursor = e; // ✅ 현재 탐색 중인 예외를 저장합니다.
        while (cursor != null) { // ✅ 원인 예외 체인을 모두 확인합니다.
            if (cursor instanceof TimeoutException || cursor instanceof SocketTimeoutException) { // ✅ 명시적인 타임아웃 유형을 우선 식별합니다.
                return true; // ✅ 타임아웃이 감지되면 즉시 true 를 반환합니다.
            }
            String message = cursor.getMessage(); // ✅ 예외 메시지에 타임아웃 단서가 있는지 확인합니다.
            if (message != null && message.toLowerCase(Locale.ROOT).contains("timeout")) { // ✅ 네트워크 스택에서 전달한 메시지도 인식합니다.
                return true; // ✅ 메시지 기반으로도 타임아웃을 감지합니다.
            }
            cursor = cursor.getCause(); // ✅ 더 깊은 원인 예외로 이동합니다.
        }
        return false; // ✅ 어떤 조건도 만족하지 않으면 타임아웃이 아님을 반환합니다.
    }
        
    /**
     * 질문 내용과 대표 출처를 활용해 앱 카드 상단에 노출할 제목을 생성합니다. // ✅ 응답 가독성을 높이기 위한 헬퍼 메서드임을 설명합니다.
     */
    private String buildAnswerTitle(String question, List<QuestionAnswerSourceDto> sources) {
        String sanitizedQuestion = question == null ? "" : question.trim(); // ✅ 공백을 제거해 깔끔한 질문 텍스트를 준비합니다.
        if (sanitizedQuestion.isEmpty()) {
            return "질문 응답"; // ✅ 질문이 비어 있을 때는 기본 제목을 제공해 UI 공백을 방지합니다.
        }
        if (sources != null && !sources.isEmpty()) {
            String source = sources.get(0).getSource(); // ✅ 대표 출처를 가져와 제목 앞부분에 노출합니다.
            if (source != null && !source.isBlank()) {
                return source + " · " + sanitizedQuestion; // ✅ 출처와 질문을 조합해 어떤 문서를 참고했는지 드러냅니다.
            }
        }
        return sanitizedQuestion; // ✅ 출처가 없으면 질문 자체를 제목으로 사용합니다.
    }

    /** 문서 인덱싱 재시도: 저장된 파일을 이용해 재업로드한다. */
    @Override
    public ApiResponseDto<Map<String, Object>> reindexDocument(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return ApiResponseDto.fail("재인덱싱 실패: UUID가 비어 있습니다.");   // ✅ 기본 파라미터 검증으로 조기 실패를 반환합니다.
        }

        Optional<Document> optionalDocument = documentRepository.findByUuid(uuid);
        if (optionalDocument.isEmpty()) {
            return ApiResponseDto.fail("재인덱싱 실패: 해당 UUID의 문서를 찾을 수 없습니다.");   // ✅ 존재하지 않는 문서에 대한 요청을 방어합니다.
        }

        Document document = optionalDocument.get();
        String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");
        if (ragBase.isBlank()) {
            log.warn("[RAG] 재인덱싱 요청 불가 uuid={} : backend-url 미설정", uuid);   // ✅ 운영 환경에서 설정 문제를 추적할 수 있도록 로그를 남깁니다.
            return ApiResponseDto.fail("재인덱싱 실패: RAG 백엔드 URL이 설정되어 있지 않습니다.");
        }

        Path filePath;
        try {
            filePath = Paths.get(document.getFilePath());   // ✅ 저장된 경로 정보를 기반으로 실제 파일을 찾습니다.
        } catch (InvalidPathException ex) {
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);   // ✅ 경로 해석 자체가 실패했음을 상태로 남깁니다.
            document.setIndexingError(truncateErrorMessage(ex.getMessage()));   // ✅ 상세 오류를 저장해 원인 분석에 활용합니다.
            documentRepository.save(document);
            return ApiResponseDto.fail("재인덱싱 실패: 저장된 파일 경로가 올바르지 않습니다.");
        }

        if (!Files.exists(filePath)) {
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);   // ✅ 파일 부재로 인한 실패 상태를 기록합니다.
            document.setIndexingError("저장된 파일을 찾을 수 없어 재인덱싱에 실패했습니다.");   // ✅ 운영자가 즉시 원인을 파악하도록 메시지를 남깁니다.
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
    
    /** 문서 삭제: 스토리지/DB/RAG 인덱스에서 모두 정리한다. */
    @Override // ✅ 삭제 로직이 인터페이스 정의와 연결됨을 표시합니다.
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
        result.put("uuid", uuid);  // 어떤 문서를 삭제했는지 응답에 함께 남긴다.
        result.put("fileName", document.getFileName());

        // 1) 스토리지에 저장된 원본 파일 삭제
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

        // 2) RAG 백엔드에 삭제 요청 (설정이 존재할 때만 호출)
        String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");
        if (!ragBase.isBlank()) {
            String url = ragBase + "/documents/delete";
            Map<String, Object> req = new HashMap<>();
            req.put("docId", uuid);
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
            result.put("ragSkipped", true);  // 설정이 없을 때는 호출을 생략했음을 명시한다.
        }

        // 3) 데이터베이스 레코드 삭제
        documentRepository.delete(document);

        return ApiResponseDto.ok(result, "문서 삭제 완료");
    }

    /**
     * 저장된 파일을 RAG 백엔드로 전송하면서 인덱싱 상태를 갱신합니다. // ✅ 업로드와 재처리 모두에서 재사용하기 위한 공통 로직입니다.
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
        document.setIndexingStatus(DocumentIndexingStatus.PROCESSING);   // ✅ 인덱싱 요청이 진행 중임을 표시합니다.
        document.setIndexingError(null);   // ✅ 이전 오류 메시지를 초기화합니다.
        documentRepository.save(document);   // ✅ 상태 변화를 DB에 즉시 반영합니다.

        String url = ragBaseUrl + "/upload";   // ✅ RAG 업로드 엔드포인트를 구성합니다.
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("file", new FileSystemResource(filePath.toFile()))
                    .filename(originalFileName)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);   // ✅ 바이너리 전송을 명시합니다.
            builder.part("docId", document.getUuid())
                    .contentType(MediaType.TEXT_PLAIN);   // ✅ 백엔드가 UUID를 식별자로 사용할 수 있도록 전달합니다.

            ragWebClient.post()
                    .uri(url)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .bodyValue(builder.build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnNext(body -> log.info("[RAG] indexing response uuid={} body={}", document.getUuid(), body))
                    .block(Duration.ofSeconds(120));   // ✅ RAG 응답을 기다리는 최대 시간을 제한합니다.

            document.setIndexingStatus(DocumentIndexingStatus.SUCCEEDED);   // ✅ 성공적으로 인덱싱되었음을 기록합니다.
            document.setIndexingError(null);   // ✅ 오류 메시지를 비웁니다.
            documentRepository.save(document);   // ✅ 결과를 지속화합니다.
            return buildPreviewResponse(document, preview, successMessage);
        } catch (Exception ex) {
            log.warn("[RAG] indexing failed uuid={} err={}", document.getUuid(), ex.toString(), ex);   // ✅ 장애 상황을 로그로 남깁니다.
            document.setIndexingStatus(DocumentIndexingStatus.FAILED);   // ✅ 실패 상태를 기록합니다.
            document.setIndexingError(truncateErrorMessage(ex.getMessage()));   // ✅ 긴 오류 메시지를 잘라 저장합니다.
            documentRepository.save(document);   // ✅ 실패 원인을 DB에 남깁니다.
            return buildPreviewResponse(document, preview, failureMessage);
        }
    }

    /**
     * 인덱싱 오류 메시지를 컬럼 길이(1000자)에 맞게 절단합니다. // ✅ DB 제약 조건 위반을 방지하기 위한 보조 메서드입니다.
     */
    private String truncateErrorMessage(String errorMessage) {
        if (errorMessage == null) {
            return null;   // ✅ 오류 메시지가 없을 때는 그대로 null 을 유지합니다.
        }
        return errorMessage.length() > 1000 ? errorMessage.substring(0, 1000) : errorMessage;   // ✅ 최대 길이를 초과하면 잘라냅니다.
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
                            if (shape instanceof XSLFTextShape) {
                                sb.append(((XSLFTextShape) shape).getText()).append("\n");
                            }
                        }
                    });
                    return sb.toString();
                }
            } else if (lower.endsWith(".docx")) {
                try (XWPFDocument doc = new XWPFDocument(file.getInputStream())) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append("\n");
                    return sb.toString();
                }
            }
        } catch (Exception e) {
            log.warn("텍스트 추출 실패: {}", e.getMessage());
        }
        return "";
    }

    private ApiResponseDto<Map<String, Object>> buildPreviewResponse(
            Document document, String preview, String message
    ) {
        Map<String, Object> response = new HashMap<>();
        response.put("uuid", document.getUuid());   // ✅ 프런트에서 문서를 식별할 수 있도록 UUID를 내려줍니다.
        response.put("fileName", document.getFileName());   // ✅ 업로드된 파일명을 그대로 전달합니다.
        response.put("previewText", preview);   // ✅ 텍스트 추출 결과를 함께 제공해 업로드 직후 확인이 가능합니다.
        response.put("indexingStatus", document.getIndexingStatus());   // ✅ 최신 인덱싱 상태를 즉시 확인할 수 있도록 포함합니다.
        response.put("indexingError", document.getIndexingError());   // ✅ 실패 시 원인을 UI에서 확인할 수 있게 합니다.
        return ApiResponseDto.ok(response, message);
    }

    /**
     * Document 엔티티를 목록용 DTO로 변환합니다.
     */
    private DocumentListItemResponseDto toListItemDto(Document document) {
        return DocumentListItemResponseDto.builder()
                .id(document.getId())   // ✅ 기본 키 매핑
                .uuid(document.getUuid())   // ✅ UUID 매핑
                .fileName(document.getFileName())   // ✅ 파일명 매핑
                .uploadedBy(document.getUploadedBy())   // ✅ 업로더 매핑
                .uploadedAt(document.getUploadedAt())   // ✅ 업로드 시간 매핑
                .size(document.getSize())   // ✅ 파일 크기 매핑
                .description(document.getDescription())   // ✅ 설명 매핑
                .indexingStatus(document.getIndexingStatus())   // ✅ 인덱싱 상태를 그대로 노출합니다.
                .indexingError(document.getIndexingError())   // ✅ 실패 시 남겨진 오류 메시지를 전달합니다.                
                .build();
    }    
}
