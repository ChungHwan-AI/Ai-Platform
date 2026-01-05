package com.buhmwoo.oneask.modules.document.api.service;

import com.buhmwoo.oneask.common.dto.ApiResponseDto;
import com.buhmwoo.oneask.common.dto.PageResponse;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentListItemResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.DocumentSuggestionResponseDto;
import com.buhmwoo.oneask.modules.document.api.dto.QuestionAnswerResponseDto; // ✅ 질문 응답 포맷을 표준화한 DTO를 사용하기 위해 임포트합니다.
import com.buhmwoo.oneask.modules.document.application.question.BotMode; // ✅ 봇 동작 모드를 전달해 fallback 정책을 제어하기 위해 임포트합니다.
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
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
     * 파일명 자동완성을 위한 추천 목록을 반환합니다. // ✅ 자동 추천 기능을 위한 서비스 계약을 정의합니다.
     */
    List<DocumentSuggestionResponseDto> getDocumentSuggestions(String keyword, int limit);

    /**
     * UUID 기준으로 저장된 파일을 다운로드합니다. // ✅ 파일 다운로드 동작을 설명합니다.
     */
    ResponseEntity<Resource> downloadFileByUuid(String uuid);

    /**
     * 특정 문서 또는 전체 문서를 대상으로 RAG 질의를 수행합니다. // ✅ 질문 처리 기능을 설명합니다.
     */
    ApiResponseDto<QuestionAnswerResponseDto> ask(String uuid, String question, BotMode mode);

    /**
     * 선택된 문서의 요약을 생성합니다. // ✅ 문서 요약 전용 기능을 정의합니다.
     */
    ApiResponseDto<QuestionAnswerResponseDto> summarizeDocument(String uuid);    

    /**
     * 문서 요약을 엑셀 파일로 내려받습니다. // ✅ 요약을 스프레드시트로 제공하는 기능을 정의합니다.
     */
    ResponseEntity<Resource> downloadSummaryExcel(String uuid);    

    /**
     * 기존 호출부 호환을 위해 기본 STRICT 모드로 질의를 수행합니다. // ✅ 모드 파라미터를 생략하더라도 이전 API가 그대로 동작함을 보장합니다.
     */
    default ApiResponseDto<QuestionAnswerResponseDto> ask(String uuid, String question) {
        return ask(uuid, question, BotMode.STRICT);
    }
        
    /**
     * 스토리지와 인덱스, DB에서 문서를 삭제합니다. // ✅ 삭제 기능의 의미를 설명합니다.
     */
    ApiResponseDto<Map<String, Object>> deleteDocument(String uuid);

    /**
     * 저장된 문서를 다시 RAG 백엔드로 전송해 인덱싱을 재시도합니다. // ✅ 업로드 실패 사례에 대응하기 위한 재처리 기능을 정의합니다.
     */
    ApiResponseDto<Map<String, Object>> reindexDocument(String uuid);  
}
