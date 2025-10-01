package com.buhmwoo.oneask.modules.document.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.multipart.MultipartFile;

public class DocumentUploadRequest {

    @Schema(type = "string", format = "binary", description = "업로드 파일")
    private MultipartFile file;

    @Schema(type = "string", description = "파일 설명")
    private String description;

    // --- Getter / Setter ---
    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
