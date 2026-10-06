package com.example.aichat.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int MAX_EXCERPT_LENGTH = 300;

    private final ChatClient chatClient;

    public ChatAnswer chat(ChatRequest request) {
        var response = chatClient.prompt()
                .user(request.message())
                .advisors(advisor -> {
                    advisor.param(ChatMemory.CONVERSATION_ID, request.conversationId());
                    if (request.documentIds() != null && !request.documentIds().isEmpty()) {
                        advisor.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, documentFilter(request.documentIds()));
                    }
                })
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null) {
            return new ChatAnswer("", List.of());
        }

        List<Document> documents = Objects.requireNonNullElse(
                response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT), List.of());

        return new ChatAnswer(
                response.getResult().getOutput().getText(),
                documents.stream().map(this::toSource).toList()
        );
    }

    private Object documentFilter(List<UUID> documentIds) {
        var filter = new FilterExpressionBuilder();
        return filter.in("documentId", documentIds.stream().map(UUID::toString).toArray()).build();
    }

    private Source toSource(Document document) {
        var metadata = document.getMetadata();
        var documentId = (String) metadata.get("documentId");
        return new Source(
                documentId,
                (String) metadata.getOrDefault("fileName", "unknown"),
                excerpt(document.getText()),
                document.getScore(),
                documentId != null ? "/api/v1/documents/%s/download".formatted(documentId) : null
        );
    }

    private String excerpt(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EXCERPT_LENGTH ? text : text.substring(0, MAX_EXCERPT_LENGTH) + "…";
    }
}
