package com.example.aichat.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    @Query("""
            select new com.example.aichat.document.DocumentResponse(d.id, d.name, d.contentType, d.uploadedAt)
            from DocumentEntity d
            order by d.uploadedAt desc
            """)
    List<DocumentResponse> findAllSummaries();
}
