package com.example.aichat.document;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String name,
        String contentType,
        LocalDateTime uploadedAt
) {}
