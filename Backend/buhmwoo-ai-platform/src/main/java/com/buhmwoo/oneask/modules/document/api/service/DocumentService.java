package com.buhmwoo.oneask.modules.document.api.service;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;

/**
 * 문서 업로드/조회/질의/삭제에 대한 서비스 계약을 정의합니다. // ✅ 새로운 서비스 계약의 의미를 설명합니다.
 */
public interface DocumentService {
    /**
     * 업로드한 파일을 저장하고 RAG 인덱싱을 트리거합니다. // ✅ 업로드 기능이 수행하는 역할을 설명합니다.
     */
    ApiResponseDto<Map<String, Object>> uploadFile(MultipartFile file, String description, String uploadedBy);

    /**
     * 문서 검색 조건과 페이지 정보를 받아 목록을 반환합니다. // ✅ 페이지 조회 기능의 의도를 설명합니다.
     */
    PageResponse<DocumentListItemResponseDto> getDocumentPage(String fileName, String uploadedBy,
                                                              LocalDate uploadedFrom, LocalDate uploadedTo,
                                                              Pageable pageable);

    /**
     * UUID 기준으로 저장된 파일을 다운로드합니다. // ✅ 파일 다운로드 동작을 설명합니다.
     */
    ResponseEntity<Resource> downloadFileByUuid(String uuid);

    /**
     * 특정 문서 또는 전체 문서를 대상으로 RAG 질의를 수행합니다. // ✅ 질문 처리 기능을 설명합니다.
     */
    ApiResponseDto<String> ask(String uuid, String question);

    /**
     * 스토리지와 인덱스, DB에서 문서를 삭제합니다. // ✅ 삭제 기능의 의미를 설명합니다.
     */
    ApiResponseDto<Map<String, Object>> deleteDocument(String uuid);     
}
