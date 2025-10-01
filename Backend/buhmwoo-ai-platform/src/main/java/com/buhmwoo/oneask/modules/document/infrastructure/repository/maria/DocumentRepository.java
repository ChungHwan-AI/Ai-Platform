package com.buhmwoo.oneask.modules.document.infrastructure.repository.maria;

import com.buhmwoo.oneask.modules.document.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    Optional<Document> findByUuid(String uuid);
}
