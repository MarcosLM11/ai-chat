package com.example.aichat.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name="documents")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String name;
    @Column(columnDefinition = "bytea")
    private byte[] data;
    private String contentType;
    @Column(length = 64, unique = true)
    private String contentHash;
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private DocumentStatus status;
    @Column(length = 1000)
    private String errorMessage;
    private LocalDateTime uploadedAt;
    private LocalDateTime finishedAt;
}
