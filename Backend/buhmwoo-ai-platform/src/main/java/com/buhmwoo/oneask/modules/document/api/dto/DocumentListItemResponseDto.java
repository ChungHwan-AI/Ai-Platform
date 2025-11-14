package com.buhmwoo.oneask.modules.document.api.dto;

import com.buhmwoo.oneask.modules.document.domain.DocumentIndexingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 목록 조회에 노출될 필드만 담아두는 간단한 DTO입니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentListItemResponseDto {

    @Schema(description = "문서 고유 ID", example = "1")
    private Long id;   // ✅ 기본 키

    @Schema(description = "문서 UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String uuid;   // ✅ 다운로드 등에 사용하는 UUID

    @Schema(description = "파일 이름", example = "report.pdf")
    private String fileName;   // ✅ 실제 파일명

    @Schema(description = "업로드 사용자", example = "alice")
    private String uploadedBy;   // ✅ 업로더 정보

    @Schema(description = "업로드 일시", example = "2025-02-01T10:15:30")
    private LocalDateTime uploadedAt;   // ✅ 업로드 시간

    @Schema(description = "파일 크기(바이트)", example = "1024")
    private Long size;   // ✅ 파일 크기

    @Schema(description = "파일 설명", example = "월간 보고서")
    private String description;   // ✅ 설명 필드

    @Schema(description = "RAG 인덱싱 상태", example = "SUCCEEDED")
    private DocumentIndexingStatus indexingStatus;   // ✅ 현재 인덱싱 진행 상황을 프런트로 전달합니다.

    @Schema(description = "최근 인덱싱 오류 메시지", example = "Connection timed out")
    private String indexingError;   // ✅ 실패 원인을 UI에서 확인할 수 있도록 노출합니다.    
}