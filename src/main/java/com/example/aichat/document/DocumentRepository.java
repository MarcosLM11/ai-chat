package com.example.aichat.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentResponse> findSummariesByOrderByUploadedAtDesc();
    Optional<DocumentResponse> findSummaryById(UUID id);

    @Query("select d.id from DocumentEntity d where d.contentHash = :contentHash")
    Optional<UUID> findIdByContentHash(String contentHash);

    @Transactional
    @Modifying
    @Query("""
            update DocumentEntity d
            set d.status = :status, d.errorMessage = :errorMessage, d.finishedAt = :finishedAt
            where d.id = :id
            """)
    int updateStatus(UUID id, DocumentStatus status, String errorMessage, LocalDateTime finishedAt);

    @Modifying
    @Query("delete from DocumentEntity d where d.id = :id")
    int deleteDocumentById(UUID id);
}
