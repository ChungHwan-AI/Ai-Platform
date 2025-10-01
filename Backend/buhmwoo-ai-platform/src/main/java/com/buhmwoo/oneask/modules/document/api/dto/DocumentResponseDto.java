package com.buhmwoo.oneask.modules.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "문서 응답 DTO")
public class DocumentResponseDto {

    @Schema(description = "문서 ID", example = "1")
    private Long id;

    @Schema(description = "파일 이름", example = "invoice.pdf")
    private String fileName;

    @Schema(description = "파일 설명", example = "거래명세서")
    private String description;

    @Schema(description = "업로드 시간", example = "2025-09-12T10:15:30")
    private LocalDateTime uploadedAt;     
    
    private String previewText; // 추출된 텍스트 앞부분 (예: 200자)
}
