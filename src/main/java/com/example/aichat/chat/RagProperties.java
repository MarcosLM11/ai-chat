package com.example.aichat.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        double similarityThreshold,
        int topK
) {}
