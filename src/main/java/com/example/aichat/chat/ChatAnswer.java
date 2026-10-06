package com.example.aichat.chat;

import java.util.List;

public record ChatAnswer(
        String answer,
        List<Source> sources
) {}
