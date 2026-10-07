package com.example.aichat.document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {
    private static final String UNEXPECTED_ERROR = "Unexpected error while processing the document";

    private final TokenTextSplitter tokenSplitter;
    private final DocumentChunkStore chunkStore;
    private final DocumentRepository documentRepository;

    @Async(DocumentConfig.INGESTION_EXECUTOR)
    @TransactionalEventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        var id = event.documentId();
        try {
            if (documentRepository.updateStatus(id, DocumentStatus.PROCESSING, null, null) == 0) {
                log.info("Document {} was deleted before processing started", id);
                return;
            }
            var entity = documentRepository.findById(id).orElseThrow();
            var chunks = tokenSplitter.apply(read(entity));
            if (chunks.isEmpty()) {
                throw new DocumentIngestionException("No text could be extracted from the document");
            }
            chunkStore.deleteByDocumentId(id);
            index(chunks);
            if (documentRepository.updateStatus(id, DocumentStatus.READY, null, LocalDateTime.now()) == 0) {
                log.info("Document {} was deleted during processing, removing its chunks", id);
                chunkStore.deleteByDocumentId(id);
            }
        } catch (Exception e) {
            log.error("Failed to process document {}", id, e);
            deleteChunksQuietly(id);
            var message = e instanceof DocumentIngestionException ? e.getMessage() : UNEXPECTED_ERROR;
            documentRepository.updateStatus(id, DocumentStatus.FAILED, message, LocalDateTime.now());
        }
    }

    private List<Document> read(DocumentEntity entity) {
        var fileName = Objects.requireNonNullElse(entity.getName(), "unknown");
        var metadata = Map.<String, Object>of(
                DocumentChunkStore.DOCUMENT_ID, entity.getId().toString(),
                TikaDocumentReader.METADATA_SOURCE, fileName,
                "fileName", fileName,
                "contentType", Objects.requireNonNullElse(entity.getContentType(), "unknown"),
                "uploadedAt", entity.getUploadedAt().toString()
        );
        List<Document> documents;
        try {
            documents = new TikaDocumentReader(new ByteArrayResource(entity.getData())).read();
        } catch (RuntimeException e) {
            throw new DocumentIngestionException("The document could not be read", e);
        }
        documents.forEach(document -> document.getMetadata().putAll(metadata));
        return documents;
    }

    private void index(List<Document> chunks) {
        try {
            chunkStore.add(chunks);
        } catch (RuntimeException e) {
            throw new DocumentIngestionException("The document could not be indexed", e);
        }
    }

    private void deleteChunksQuietly(UUID id) {
        try {
            chunkStore.deleteByDocumentId(id);
        } catch (Exception e) {
            log.warn("Could not delete chunks of failed document {}", id, e);
        }
    }
}