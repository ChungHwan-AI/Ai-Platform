package com.buhmwoo.oneask.modules.document.application.impl;

import com.buhmwoo.oneask.common.config.OneAskProperties;
import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.modules.document.domain.Document;
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
import org.springframework.http.*;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 업로드 → 디스크 저장 → DB기록 → (선택) RAG 인덱싱 트리거
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    private final DocumentRepository documentRepository;
    private final OneAskProperties props;

    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create().responseTimeout(Duration.ofSeconds(120))
            ))
            .exchangeStrategies(ExchangeStrategies.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(256 * 1024 * 1024))
                    .build())
            .build();

    /** 업로드(+DB 저장) → FastAPI(/upload, multipart) 전송 → 인덱싱 트리거 */
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
                    .build();
            documentRepository.save(doc);

            // 5) 프리뷰 텍스트(선택)
            String extractedText = extractText(file);
            String preview = (extractedText != null && extractedText.length() > 200)
                    ? extractedText.substring(0, 200) + "..." : extractedText;

            // 6) RAG 인덱싱 (ragBase 없으면 생략)
            if (!ragBase.isBlank()) {
                String url = ragBase + "/upload"; // 예: http://rag-backend:8000/upload (컨테이너 네트워크)
                try {
                    MultipartBodyBuilder mb = new MultipartBodyBuilder();
                    mb.part("file", new FileSystemResource(target.toFile()))
                      .filename(safeName)
                      .contentType(MediaType.APPLICATION_OCTET_STREAM);
                    mb.part("docId", uuid)
                      .contentType(MediaType.TEXT_PLAIN);

                    webClient.post()
                        .uri(url)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .bodyValue(mb.build())
                        .retrieve()
                        .bodyToMono(String.class)
                        .doOnNext(b -> log.info("[RAG] status=200 body={}", b))
                        .block(Duration.ofSeconds(120)); // ← 변수에 할당 안 함

                    return buildPreviewResponse(uuid, safeName, preview,
                            "파일 업로드 + 인덱싱 요청 완료: " + safeName);
                } catch (Exception ex) {
                    log.warn("[RAG] 업로드 예외: {}", ex.toString(), ex);
                    return buildPreviewResponse(uuid, safeName, preview,
                            "파일 업로드 완료(인덱싱 요청 실패): " + safeName);
                }
            } else {
                return buildPreviewResponse(uuid, safeName, preview,
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

    /** 다운로드 (UUID 기반) */
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

    /** 문서 기반 질의: FastAPI /query */
    public ApiResponseDto<String> ask(String uuid, String question) {
        try {
            String ragBase = Optional.ofNullable(props.getRag()).map(OneAskProperties.Rag::getBackendUrl).orElse("");
            if (ragBase.isBlank()) return ApiResponseDto.fail("질의 실패: custom.rag.backend-url 이 비었습니다.");
            
            System.out.println("[DEBUG] RAG Base URL: " + ragBase);  // ← 로그 추가

            String url = ragBase + "/query";

            System.out.println("[DEBUG] Full URL: " + url);  // ← 로그 추가

            Map<String, Object> req = new HashMap<>();
            req.put("question", question);
            req.put("docId", uuid);
            req.put("top_k", 3);
            
            System.out.println("[DEBUG] Request body: " + req);  // ← 로그 추가

            String answer = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .doOnError(e -> System.err.println("[ERROR] WebClient error: " + e.getMessage()))  // ← 에러 로그
                    .map(m -> String.valueOf(m.get("answer")))
                    .block(Duration.ofSeconds(120));
                 
            if (answer == null) return ApiResponseDto.fail("응답 실패: answer 없음");
            return ApiResponseDto.ok(answer, "응답 성공");

        } catch (Exception e) {
            log.error("문서 질의 실패: {}", e.getMessage());
            return ApiResponseDto.fail("질의 실패: " + e.getMessage());
        }
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
            String uuid, String fileName, String preview, String message
    ) {
        Map<String, Object> response = new HashMap<>();
        response.put("uuid", uuid);
        response.put("fileName", fileName);
        response.put("previewText", preview);
        return ApiResponseDto.ok(response, message);
    }
}
