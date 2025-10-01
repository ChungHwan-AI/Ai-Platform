package com.buhmwoo.oneask.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 표준 페이징 응답 DTO.
 * - page는 0-based 인덱스입니다.
 * - Spring Data Page/Pageable을 바로 매핑할 수 있는 팩토리 메서드 제공.
 * - content 타입 변환을 위한 map(mapper) 제공.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageResponse<T> {

    private final List<T> content;

    /** 현재 페이지 번호(0-based) */
    private final int page;

    /** 페이지 크기(page size) */
    private final int size;

    /** 전체 데이터 건수 */
    private final long totalElements;

    /** 전체 페이지 수 */
    private final int totalPages;

    private final boolean first;
    private final boolean last;
    private final boolean hasNext;
    private final boolean hasPrev;

    /** 정렬 정보(선택) */
    private final SortSpec sort;

    /* ------------ 생성자 & 정적 팩토리 ------------ */

    private PageResponse(List<T> content,
                         int page,
                         int size,
                         long totalElements,
                         int totalPages,
                         boolean first,
                         boolean last,
                         boolean hasNext,
                         boolean hasPrev,
                         SortSpec sort) {
        this.content = content == null ? Collections.emptyList() : Collections.unmodifiableList(content);
        this.page = Math.max(page, 0);
        this.size = Math.max(size, 0);
        this.totalElements = Math.max(totalElements, 0L);
        this.totalPages = Math.max(totalPages, 0);
        this.first = first;
        this.last = last;
        this.hasNext = hasNext;
        this.hasPrev = hasPrev;
        this.sort = sort;
    }

    /**
     * Spring Data Page → PageResponse
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        if (page == null) return empty();
        SortSpec sortSpec = SortSpec.from(page.getSort());
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.hasNext(),
                page.hasPrevious(),
                sortSpec
        );
    }

    /**
     * 임의의 목록/카운트로 PageResponse 구성 (정렬 선택)
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements, Sort sort) {
        int safeSize = Math.max(size, 0);
        long safeTotal = Math.max(totalElements, 0L);
        int computedTotalPages;

        if (safeSize <= 0) {
            // size가 0이거나 미지정인 경우: 총 페이지를 1(데이터가 있으면) 또는 0(없으면)로 처리
            computedTotalPages = safeTotal > 0 ? 1 : 0;
        } else {
            computedTotalPages = (int) ((safeTotal + safeSize - 1) / safeSize);
        }

        boolean isFirst = page <= 0 || computedTotalPages == 0;
        boolean isLast = computedTotalPages == 0 || page >= computedTotalPages - 1;
        boolean hasPrev = page > 0 && computedTotalPages > 0;
        boolean hasNext = (page + 1) < computedTotalPages;

        return new PageResponse<>(
                content == null ? Collections.emptyList() : content,
                Math.max(page, 0),
                safeSize,
                safeTotal,
                computedTotalPages,
                isFirst,
                isLast,
                hasNext,
                hasPrev,
                SortSpec.from(sort)
        );
    }

    /**
     * Pageable + 총카운트로 PageResponse 구성 (정렬은 pageable 사용)
     */
    public static <T> PageResponse<T> of(List<T> content, Pageable pageable, long totalElements) {
        if (pageable == null) {
            return of(content, 0, content == null ? 0 : content.size(), totalElements, Sort.unsorted());
        }
        return of(content, pageable.getPageNumber(), pageable.getPageSize(), totalElements, pageable.getSort());
    }

    /** 빈 페이지 응답 */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(
                Collections.emptyList(),
                0, 0, 0, 0,
                true, true, false, false,
                null
        );
    }

    /** Pageable 기준으로 빈 페이지 응답 */
    public static <T> PageResponse<T> empty(Pageable pageable) {
        if (pageable == null) return empty();
        return new PageResponse<>(
                Collections.emptyList(),
                pageable.getPageNumber(),
                pageable.getPageSize(),
                0, 0,
                true, true, false, false,
                SortSpec.from(pageable.getSort())
        );
    }

    /* ------------ 변환(map) & 래핑 ------------ */

    /** content 타입 변환(메타데이터 유지) */
    public <R> PageResponse<R> map(Function<T, R> mapper) {
        if (mapper == null) {
            return new PageResponse<>(
                    Collections.emptyList(), page, size, totalElements, totalPages, first, last, hasNext, hasPrev, sort
            );
        }
        List<R> mapped = new ArrayList<>(content.size());
        for (T t : content) mapped.add(mapper.apply(t));

        return new PageResponse<>(
                mapped, page, size, totalElements, totalPages, first, last, hasNext, hasPrev, sort
        );
    }

    /** ApiResponse 래핑 헬퍼 */
    public ApiResponseDto<PageResponse<T>> toApiResponse() {
        return ApiResponseDto.ok(this);
    }

    /* ------------ 정렬 스펙 ------------ */

    public static class SortSpec {
        private final List<Order> orders;

        private SortSpec(List<Order> orders) {
            this.orders = orders == null ? Collections.emptyList() : Collections.unmodifiableList(orders);
        }

        public static SortSpec from(Sort sort) {
            if (sort == null || sort.isUnsorted()) return null;
            List<Order> list = new ArrayList<>();
            for (Sort.Order o : sort) {
                list.add(new Order(o.getProperty(), o.getDirection() == null ? "ASC" : o.getDirection().name()));
            }
            return list.isEmpty() ? null : new SortSpec(list);
        }

        public List<Order> getOrders() {
            return orders;
        }

        public static class Order {
            private final String property;
            private final String direction; // "ASC" | "DESC"

            public Order(String property, String direction) {
                this.property = property;
                this.direction = direction;
            }

            public String getProperty() {
                return property;
            }

            public String getDirection() {
                return direction;
            }
        }
    }

    /* ------------ Getters (직렬화용) ------------ */

    public List<T> getContent() {
        return content;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public boolean isFirst() {
        return first;
    }

    public boolean isLast() {
        return last;
    }

    public boolean isHasNext() {
        return hasNext;
    }

    public boolean isHasPrev() {
        return hasPrev;
    }

    public SortSpec getSort() {
        return sort;
    }
}
