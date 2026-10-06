package com.example.aichat.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private static final int MAX_EXCERPT_LENGTH = 300;

    private final ChatClient chatClient;

    public ChatAnswer chat(UUID conversationId, ChatRequest request) {
        var response = prompt(conversationId, request)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null) {
            return new ChatAnswer("", List.of());
        }

        List<Document> documents = Objects.requireNonNullElse(
                response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT), List.of());

        return new ChatAnswer(
                response.getResult().getOutput().getText(),
                toSources(documents)
        );
    }

    public Flux<ServerSentEvent<?>> chatStream(UUID conversationId, ChatRequest request) {
        return prompt(conversationId, request)
                .stream()
                .chatClientResponse()
                .index()
                .concatMap(indexed -> indexed.getT1() == 0
                        ? Flux.concat(sourcesEvent(indexed.getT2()), tokenEvent(indexed.getT2()))
                        : tokenEvent(indexed.getT2()))
                .concatWith(Flux.just(ServerSentEvent.builder(Map.of()).event("done").build()))
                .onErrorResume(error -> {
                    log.error("Error streaming conversation {}", conversationId, error);
                    return Flux.just(ServerSentEvent.builder(new ChatError("Error generating the response")).event("error").build());
                });
    }

    private ChatClient.ChatClientRequestSpec prompt(UUID conversationId, ChatRequest request) {
        return chatClient.prompt()
                .user(request.message())
                .advisors(advisor -> {
                    advisor.param(ChatMemory.CONVERSATION_ID, conversationId.toString());
                    if (request.documentIds() != null && !request.documentIds().isEmpty()) {
                        advisor.param(VectorStoreDocumentRetriever.FILTER_EXPRESSION, documentFilter(request.documentIds()));
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private Flux<ServerSentEvent<?>> sourcesEvent(ChatClientResponse response) {
        var documents = (List<Document>) Objects.requireNonNullElse(
                response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT), List.of());
        return Flux.just(ServerSentEvent.builder(toSources(documents)).event("sources").build());
    }

    private Flux<ServerSentEvent<?>> tokenEvent(ChatClientResponse response) {
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return Flux.empty();
        }
        var text = chatResponse.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }
        return Flux.just(ServerSentEvent.builder(new ChatToken(text)).event("token").build());
    }

    private Object documentFilter(List<UUID> documentIds) {
        var filter = new FilterExpressionBuilder();
        return filter.in("documentId", documentIds.stream().map(UUID::toString).toArray()).build();
    }

    private List<Source> toSources(List<Document> documents) {
        return documents.stream().map(this::toSource).toList();
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
