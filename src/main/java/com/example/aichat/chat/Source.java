package com.example.aichat.chat;

public record Source(
        String documentId,
        String fileName,
        String excerpt,
        Double score,
        String downloadUrl
) {}
