package com.buhmwoo.oneask.modules.document.api.service;

import com.buhmwoo.oneask.modules.document.api.dto.DocumentResponseDto;
import com.buhmwoo.oneask.modules.document.domain.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    Document uploadDocument(MultipartFile file, String uploadedBy);
    DocumentResponseDto saveDocument(MultipartFile file, String description);
    DocumentResponseDto getDocument(Long id);
    void deleteDocument(Long id);   
     
}
