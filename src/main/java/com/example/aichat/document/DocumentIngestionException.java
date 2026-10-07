package com.example.aichat.document;

public class DocumentIngestionException extends RuntimeException {
    DocumentIngestionException(String message) {
        super(message);
    }

    DocumentIngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}