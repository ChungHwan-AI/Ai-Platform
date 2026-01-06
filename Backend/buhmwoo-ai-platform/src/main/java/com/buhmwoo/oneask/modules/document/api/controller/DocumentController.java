package com.buhmwoo.oneask.modules.document.api.controller;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentPageResponseDocs;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentSuggestionResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerResponseDto; // ✅ GPT 응답 포맷을 재사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.api.dto.QuestionRequestDto; // ✅ POST 본문으로 질문을 받을 때 사용합니다.
import com.buhmwoo.oneask.modules.document.api.service.DocumentService;
import com.buhmwoo.oneask.modules.document.application.question.BotMode; // ✅ fallback 모드 선택을 위해 Enum 을 컨트롤러에 노출합니다.
import jakarta.validation.Valid; // ✅ POST 요청 본문 검증을 위해 추가합니다.

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse; // ✅ 추가
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.*;
import java.time.LocalDate;

@Tag(name = "Document", description = "문서 업로드/다운로드 API")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService; // ✅ 구현체 대신 인터페이스에 의존하도록 변경합니다.    

    public DocumentController(DocumentService documentService) { // ✅ 스프링이 인터페이스 타입으로 주입하도록 구성합니다.
        this.documentService = documentService;
    }

    @Operation(summary = "파일 업로드", description = "파일 업로드 후 UUID/파일명/추출텍스트 미리보기를 반환합니다.")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseDto<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "uploadedBy", defaultValue = "system") String uploadedBy) {
        return documentService.uploadFile(file, description, uploadedBy);
    }

    @Operation(
            summary = "문서 목록 조회",
            description = "파일명/작성자/업로드일 조건과 페이징 정보를 이용해 문서 목록을 조회합니다.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "문서 목록 조회 성공",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = DocumentPageResponseDocs.class),
                                    examples = @ExampleObject(
                                            name = "기본 응답",
                                            value = "{\n  \"success\": true,\n  \"message\": \"문서 목록 조회 성공\",\n  \"data\": {\n    \"content\": [\n      {\n        \"id\": 1,\n        \"uuid\": \"550e8400-e29b-41d4-a716-446655440000\",\n        \"fileName\": \"report.pdf\",\n        \"uploadedBy\": \"alice\",\n        \"uploadedAt\": \"2025-02-01T10:15:30\",\n        \"size\": 1024,\n        \"description\": \"월간 보고서\",\n        \"indexingStatus\": \"SUCCEEDED\",\n        \"indexingError\": null\n      }\n    ],\n    \"page\": 0,\n    \"size\": 10,\n    \"totalElements\": 1,\n    \"totalPages\": 1,\n    \"first\": true,\n    \"last\": true,\n    \"hasNext\": false,\n    \"hasPrev\": false,\n    \"sort\": {\n      \"orders\": [\n        {\n          \"property\": \"uploadedAt\",\n          \"direction\": \"DESC\"\n        }\n      ]\n    }\n  }\n}"
                                    )
                            )
                    )
            }
    )
    @GetMapping
    public ApiResponseDto<PageResponse<DocumentListItemResponseDto>> getDocuments(
            @RequestParam(value = "fileName", required = false) String fileName,   // ✅ 파일명 검색 파라미터
            @RequestParam(value = "uploadedBy", required = false) String uploadedBy,   // ✅ 업로더 검색 파라미터
            @RequestParam(value = "uploadedFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedFrom,   // ✅ 업로드 시작일 검색 파라미터
            @RequestParam(value = "uploadedTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate uploadedTo,   // ✅ 업로드 종료일 검색 파라미터
            @ParameterObject @PageableDefault(sort = "uploadedAt", direction = Sort.Direction.DESC) Pageable pageable   // ✅ 기본 정렬을 업로드일 내림차순으로 설정
    ) {
        PageResponse<DocumentListItemResponseDto> page = documentService.getDocumentPage(fileName, uploadedBy, uploadedFrom, uploadedTo, pageable);   // ✅ 서비스로 위임하여 조회
        return ApiResponseDto.ok(page, "문서 목록 조회 성공");   // ✅ 표준 ApiResponseDto 래핑
    }
    
    @Operation(summary = "문서 자동완성 추천", description = "파일명 키워드로 자동완성 추천 목록을 반환합니다.")
    @GetMapping("/suggest")
    public ApiResponseDto<List<DocumentSuggestionResponseDto>> getSuggestions(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        List<DocumentSuggestionResponseDto> suggestions =
                documentService.getDocumentSuggestions(keyword, limit);
        return ApiResponseDto.ok(suggestions, "문서 추천 목록 조회 성공");
    }

    @Operation(
        summary = "파일 다운로드 (UUID)",
        description = "UUID를 기준으로 파일을 다운로드합니다.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "파일 다운로드 성공",
                content = @Content(
                    mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")
                )
            )
        }
    )
    @GetMapping(value = "/download/{uuid}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Resource> downloadFileByUuid(@PathVariable String uuid) {
        return documentService.downloadFileByUuid(uuid);
    }

    @Operation(
        summary = "파일 미리보기 (UUID)",
        description = "UUID를 기준으로 브라우저에서 인라인 미리보기를 제공합니다.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "파일 미리보기 성공",
                content = @Content(
                    mediaType = "application/octet-stream",
                    schema = @Schema(type = "string", format = "binary")
                )
            )
        }
    )
    @GetMapping("/preview/{uuid}")
    public ResponseEntity<Resource> previewFileByUuid(@PathVariable String uuid) {
        return documentService.previewFileByUuid(uuid);
    }
        
    @Operation(summary = "문서 기반 질문", description = "업로드된 문서(UUID) 범위에서 질문에 답합니다.")

    @GetMapping("/{uuid}/ask")
    public ApiResponseDto<QuestionAnswerResponseDto> ask(@PathVariable String uuid,
                                                         @RequestParam String question,
                                                         @RequestParam(name = "mode", required = false, defaultValue = "STRICT") BotMode mode) {
        return documentService.ask(uuid, question, mode);
    }

    @Operation(summary = "문서 요약", description = "선택된 문서를 요약해 제공합니다.")
    @GetMapping("/{uuid}/summary")
    public ApiResponseDto<QuestionAnswerResponseDto> summarize(@PathVariable String uuid) {
        return documentService.summarizeDocument(uuid);
    }
    
    @Operation(summary = "문서 요약 엑셀 다운로드", description = "선택된 문서 요약을 엑셀 파일로 다운로드합니다.")
    @GetMapping(
        value = "/{uuid}/summary.xlsx",
        produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    public ResponseEntity<Resource> downloadSummaryExcel(@PathVariable String uuid) {
        return documentService.downloadSummaryExcel(uuid);
    }
        
    @PostMapping("/{uuid}/ask")
    public ApiResponseDto<QuestionAnswerResponseDto> askPost(@PathVariable String uuid,
                                                             @Valid @RequestBody QuestionRequestDto payload) {
        BotMode mode = payload.mode() == null ? BotMode.STRICT : payload.mode();
        return documentService.ask(uuid, payload.question(), mode);  // ✅ JSON 본문을 통한 POST 호출을 지원합니다.
    }

    @Operation(summary = "문서 전체 질문", description = "특정 문서를 지정하지 않고 업로드된 모든 문서를 대상으로 질문에 답합니다.")

    @GetMapping("/ask")
    public ApiResponseDto<QuestionAnswerResponseDto> askAll(@RequestParam String question,
                                                            @RequestParam(name = "mode", required = false, defaultValue = "STRICT") BotMode mode) {
        return documentService.ask(null, question, mode);  // ✅ UUID 없이 호출해 전체 문서를 대상으로 유사도 검색을 수행하도록 위임합니다.
    }
    
    @PostMapping("/ask")
    public ApiResponseDto<QuestionAnswerResponseDto> askAllPost(@Valid @RequestBody QuestionRequestDto payload) {
        BotMode mode = payload.mode() == null ? BotMode.STRICT : payload.mode();
        return documentService.ask(null, payload.question(), mode);  // ✅ POST JSON 요청도 동일한 파이프라인으로 처리합니다.
    }
        
    @Operation(summary = "문서 인덱싱 재시도", description = "저장된 파일을 이용해 RAG 인덱싱을 다시 요청합니다.")
    @PostMapping("/{uuid}/reindex")
    public ApiResponseDto<Map<String, Object>> reindexDocument(@PathVariable String uuid) {
        return documentService.reindexDocument(uuid);  // ✅ 서비스에서 인덱싱 상태 갱신 및 재전송을 처리하도록 위임합니다.
    }
        
    @Operation(summary = "문서 삭제", description = "UUID를 기준으로 스토리지 및 RAG 인덱스에서 문서를 삭제합니다.")
    @DeleteMapping("/{uuid}")
    public ApiResponseDto<Map<String, Object>> deleteDocument(@PathVariable String uuid) {
        return documentService.deleteDocument(uuid);  // ✅ 서비스 계층에서 스토리지/DB/RAG 삭제를 한 번에 수행하도록 위임합니다.
    }
}
