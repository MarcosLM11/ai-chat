package com.example.aichat.document;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@EnableAsync
@Configuration
public class DocumentConfig {
    static final String INGESTION_EXECUTOR = "documentIngestionExecutor";
    private static final int INGESTION_CONCURRENCY = 2;

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(5)
                .withMaxNumChunks(1000)
                .withKeepSeparator(true)
                .build();
    }

    @Bean(INGESTION_EXECUTOR)
    public ThreadPoolTaskExecutor documentIngestionExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("document-ingestion-");
        executor.setCorePoolSize(INGESTION_CONCURRENCY);
        executor.setMaxPoolSize(INGESTION_CONCURRENCY);
        return executor;
    }
}