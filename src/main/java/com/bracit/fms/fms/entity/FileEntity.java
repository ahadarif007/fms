package com.bracit.fms.fms.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "files", indexes = {@Index(name = "idx_file_type", columnList = "fileType"), @Index(name = "idx_provider", columnList = "provider"), @Index(name = "idx_created_at", columnList = "createdAt")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String originalFileName;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private Long size;

    @Column(nullable = false, length = 1000)
    private String path;

    @Column(length = 1000)
    private String thumbnailPath;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Boolean isImage;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String fileType;
}