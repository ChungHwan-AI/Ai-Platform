package com.buhmwoo.oneask.modules.document.infrastructure.repository.maria;

import com.buhmwoo.oneask.modules.document.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findByUuid(String uuid);

    /**
     * 파일명/작성자/업로드일 조건으로 문서 목록을 검색하면서 페이징 정보를 함께 반환합니다.
     */
    @Query("""
            SELECT d
            FROM Document d
            WHERE (:fileName IS NULL OR LOWER(d.fileName) LIKE LOWER(CONCAT('%', :fileName, '%')))
              AND (:uploadedBy IS NULL OR LOWER(d.uploadedBy) LIKE LOWER(CONCAT('%', :uploadedBy, '%')))
              AND (:uploadedFrom IS NULL OR d.uploadedAt >= :uploadedFrom)
              AND (:uploadedTo IS NULL OR d.uploadedAt <= :uploadedTo)
            """)
    Page<Document> searchDocuments(
            @Param("fileName") String fileName,   // ✅ 파일명 검색어 조건
            @Param("uploadedBy") String uploadedBy,   // ✅ 업로더 검색어 조건
            @Param("uploadedFrom") LocalDateTime uploadedFrom,   // ✅ 업로드 시작일 조건
            @Param("uploadedTo") LocalDateTime uploadedTo,   // ✅ 업로드 종료일 조건
            Pageable pageable   // ✅ 페이징 및 정렬 정보
    );    
    
    List<Document> findAllByFileNameIgnoreCase(String fileName);    
}
