package com.example.aichat.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record ChatRequest(
        @NotBlank String conversationId,
        @NotBlank String message,
        List<@NotNull UUID> documentIds
) {}
