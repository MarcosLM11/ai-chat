package com.example.aichat.document;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String name,
        String contentType,
        DocumentStatus status,
        String errorMessage,
        LocalDateTime uploadedAt,
        LocalDateTime finishedAt
) {
    static DocumentResponse from(DocumentEntity entity) {
        return new DocumentResponse(
                entity.getId(),
                entity.getName(),
                entity.getContentType(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getUploadedAt(),
                entity.getFinishedAt()
        );
    }
}