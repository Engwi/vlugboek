package za.co.vlugboek.service;

import java.time.Instant;
import za.co.vlugboek.domain.DocumentRecord;

public class DuplicateDocumentException extends RuntimeException {
    private final Long documentId;
    private final String documentTitle;
    private final String originalFilename;
    private final Instant uploadedAt;

    public DuplicateDocumentException(DocumentRecord document) {
        super("This PDF was already uploaded as \"" + document.getTitle() + "\".");
        this.documentId = document.getId();
        this.documentTitle = document.getTitle();
        this.originalFilename = document.getOriginalFilename();
        this.uploadedAt = document.getUploadedAt();
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocumentTitle() {
        return documentTitle;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
