package com.buhmwoo.oneask.modules.document.api.controller;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.modules.document.application.impl.DocumentServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse; // ✅ 추가
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Tag(name = "Document", description = "문서 업로드/다운로드 API")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentServiceImpl documentService;

    public DocumentController(DocumentServiceImpl documentService) {
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

    @Operation(summary = "문서 기반 질문", description = "업로드된 문서(UUID) 범위에서 질문에 답합니다.")

    @GetMapping("/{uuid}/ask")
    public ApiResponseDto<String> ask(@PathVariable String uuid, @RequestParam String question) {
        return documentService.ask(uuid, question);
    }

    @Operation(summary = "문서 전체 질문", description = "특정 문서를 지정하지 않고 업로드된 모든 문서를 대상으로 질문에 답합니다.")

    @GetMapping("/ask")
    public ApiResponseDto<String> askAll(@RequestParam String question) {
        return documentService.ask(null, question);  // ✅ UUID 없이 호출해 전체 문서를 대상으로 유사도 검색을 수행하도록 위임합니다.
    }
    
    @Operation(summary = "문서 삭제", description = "UUID를 기준으로 스토리지 및 RAG 인덱스에서 문서를 삭제합니다.")
    @DeleteMapping("/{uuid}")
    public ApiResponseDto<Map<String, Object>> deleteDocument(@PathVariable String uuid) {
        return documentService.deleteDocument(uuid);  // ✅ 서비스 계층에서 스토리지/DB/RAG 삭제를 한 번에 수행하도록 위임합니다.
    }
}
