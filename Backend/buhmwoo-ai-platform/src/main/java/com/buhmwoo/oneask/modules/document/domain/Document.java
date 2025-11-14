package com.buhmwoo.oneask.modules.document.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 문서 메타데이터와 인덱싱 상태를 함께 보관하는 JPA 엔티티입니다. // ✅ 인덱싱 상태 추적을 위해 엔티티 설명을 보강합니다.
 */

@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String uuid;   // ✅ UUID 필드 추가

    @Column(name = "filename", nullable = false, length = 255)
    private String fileName;

    @Column(name = "filepath", nullable = false, length = 500)
    private String filePath;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size", nullable = false)
    private Long size;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "indexing_status", nullable = false, length = 20)
    private DocumentIndexingStatus indexingStatus;   // ✅ 현재 문서가 어떤 인덱싱 단계에 있는지를 기록합니다.

    @Column(name = "indexing_error", length = 1000)
    private String indexingError;   // ✅ 인덱싱 실패 시 원인을 추적하기 위한 에러 메시지를 저장합니다.    
}

