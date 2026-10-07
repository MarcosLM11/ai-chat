package com.example.aichat.document;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DocumentChunkStore {
    static final String DOCUMENT_ID = "documentId";

    private final VectorStore vectorStore;

    public void add(List<Document> chunks) {
        vectorStore.add(chunks);
    }

    public void deleteByDocumentId(UUID documentId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(DOCUMENT_ID, documentId.toString()).build());
    }
}
