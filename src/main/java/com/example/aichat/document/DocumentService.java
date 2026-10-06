package com.example.aichat.document;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {
    private final TokenTextSplitter tokenSplitter;
    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;

    @Transactional
    public DocumentResponse upload(MultipartFile file) {
        var reader = new TikaDocumentReader(file.getResource());
        var documents = reader.read();

        var entity = DocumentEntity.builder()
                .name(file.getOriginalFilename())
                .contentType(file.getContentType())
                .data(readBytes(file))
                .uploadedAt(LocalDateTime.now())
                .build();
        documentRepository.save(entity);

        documents.forEach(document -> document.getMetadata().putAll(Map.of(
                "documentId", entity.getId().toString(),
                "fileName", Objects.requireNonNullElse(entity.getName(), "unknown"),
                "contentType", Objects.requireNonNullElse(entity.getContentType(), "unknown"),
                "uploadedAt", entity.getUploadedAt().toString()
        )));

        var chunks = tokenSplitter.apply(documents);
        vectorStore.add(chunks);

        return new DocumentResponse(entity.getId(), entity.getName(), entity.getContentType(), entity.getUploadedAt());
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> findAll() {
        return documentRepository.findAllSummaries();
    }

    @Transactional
    public void delete(UUID id) {
        if (!documentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        var filter = new FilterExpressionBuilder();
        vectorStore.delete(filter.eq("documentId", id.toString()).build());
        documentRepository.deleteById(id);
    }

    public DocumentEntity download(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo '%s'".formatted(file.getOriginalFilename()), e);
        }
    }
}
