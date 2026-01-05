package com.buhmwoo.oneask.modules.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자동완성 추천용 간단 DTO입니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentSuggestionResponseDto {

    @Schema(description = "문서 UUID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String uuid;

    @Schema(description = "파일 이름", example = "report.pdf")
    private String fileName;

    @Schema(description = "업로드 일시", example = "2025-02-01T10:15:30")
    private LocalDateTime uploadedAt;

    @Schema(description = "파일 설명", example = "월간 보고서")
    private String description;
}