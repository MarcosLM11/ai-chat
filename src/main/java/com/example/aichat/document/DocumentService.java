package com.example.aichat.document;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {
    private static final String CONTENT_HASH_CONSTRAINT = "documents_content_hash_key";

    private final DocumentChunkStore chunkStore;
    private final DocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public DocumentResponse upload(MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The file is empty");

        var data = readBytes(file);
        var contentHash = sha256(data);
        var existingId = documentRepository.findIdByContentHash(contentHash);
        if (existingId.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The document was already uploaded with id %s".formatted(existingId.get()));
        }

        var entity = DocumentEntity.builder()
                .name(file.getOriginalFilename())
                .contentType(file.getContentType())
                .data(data)
                .contentHash(contentHash)
                .status(DocumentStatus.PENDING)
                .uploadedAt(LocalDateTime.now())
                .build();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                documentRepository.saveAndFlush(entity);
                eventPublisher.publishEvent(new DocumentUploadedEvent(entity.getId()));
            });
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateContentHash(e)) throw e;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The document was already uploaded");
        }

        return DocumentResponse.from(entity);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> findAll() {
        return documentRepository.findSummariesByOrderByUploadedAtDesc();
    }

    @Transactional(readOnly = true)
    public DocumentResponse findById(UUID id) {
        return documentRepository.findSummaryById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    @Transactional
    public void delete(UUID id) {
        if (documentRepository.deleteDocumentById(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }
        chunkStore.deleteByDocumentId(id);
    }

    public DocumentEntity download(UUID id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    private boolean isDuplicateContentHash(DataIntegrityViolationException e) {
        return e.getCause() instanceof ConstraintViolationException violation
                && CONTENT_HASH_CONSTRAINT.equals(violation.getConstraintName());
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo '%s'".formatted(file.getOriginalFilename()), e);
        }
    }
}
