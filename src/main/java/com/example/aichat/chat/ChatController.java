package com.example.aichat.chat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import java.util.UUID;

@RestController
@RequestMapping("api/v1/conversations")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService service;

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ChatAnswer> chat(@PathVariable UUID conversationId, @Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(service.chat(conversationId, request));
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> chatStream(@PathVariable UUID conversationId, @Valid @RequestBody ChatRequest request) {
        return service.chatStream(conversationId, request);
    }
}
