package com.example.aichat.chat;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/chat")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService service;

    @PostMapping
    public ResponseEntity<ChatAnswer> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(service.chat(request));
    }
}
