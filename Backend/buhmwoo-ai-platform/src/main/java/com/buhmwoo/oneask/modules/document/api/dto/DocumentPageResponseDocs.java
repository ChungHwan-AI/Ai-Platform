package com.buhmwoo.oneask.modules.document.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Swagger 예시 응답 작성을 위한 도우미 DTO입니다.
 */
@Getter
@Setter
@Schema(description = "문서 목록 조회 응답 예시")
public class DocumentPageResponseDocs {

    @Schema(description = "성공 여부", example = "true")
    private boolean success;   // ✅ ApiResponseDto.success 필드 예시

    @Schema(description = "메시지", example = "문서 목록 조회 성공")
    private String message;   // ✅ ApiResponseDto.message 필드 예시

    @Schema(description = "페이지 데이터")
    private PageData data;   // ✅ 실제 PageResponse 데이터를 감싸는 필드

    @Getter
    @Setter
    @Schema(description = "페이지 정보")
    public static class PageData {

        @ArraySchema(
                arraySchema = @Schema(description = "문서 목록"),
                schema = @Schema(implementation = DocumentListItemResponseDto.class)
        )
        private List<DocumentListItemResponseDto> content;   // ✅ 페이지 콘텐츠

        @Schema(description = "현재 페이지", example = "0")
        private int page;   // ✅ 현재 페이지 번호

        @Schema(description = "페이지 크기", example = "10")
        private int size;   // ✅ 페이지 당 데이터 수

        @Schema(description = "전체 데이터 수", example = "25")
        private long totalElements;   // ✅ 전체 건수

        @Schema(description = "전체 페이지 수", example = "3")
        private int totalPages;   // ✅ 전체 페이지 수

        @Schema(description = "첫 페이지 여부", example = "true")
        private boolean first;   // ✅ 첫 페이지 여부

        @Schema(description = "마지막 페이지 여부", example = "false")
        private boolean last;   // ✅ 마지막 페이지 여부

        @Schema(description = "다음 페이지 존재 여부", example = "true")
        private boolean hasNext;   // ✅ 다음 페이지 존재 여부

        @Schema(description = "이전 페이지 존재 여부", example = "false")
        private boolean hasPrev;   // ✅ 이전 페이지 존재 여부

        @Schema(description = "정렬 정보")
        private SortSpec sort;   // ✅ 정렬 정보 컨테이너
    }

    @Getter
    @Setter
    @Schema(description = "정렬 정보")
    public static class SortSpec {

        @ArraySchema(
                arraySchema = @Schema(description = "정렬 조건 목록"),
                schema = @Schema(implementation = Order.class)
        )
        private List<Order> orders;   // ✅ 개별 정렬 조건들

        @Getter
        @Setter
        @Schema(description = "단일 정렬 조건")
        public static class Order {

            @Schema(description = "정렬 대상 컬럼", example = "uploadedAt")
            private String property;   // ✅ 정렬 대상 컬럼명

            @Schema(description = "정렬 방향", example = "DESC")
            private String direction;   // ✅ 정렬 방향
        }
    }
}