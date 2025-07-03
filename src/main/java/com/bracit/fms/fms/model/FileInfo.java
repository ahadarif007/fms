package com.bracit.fms.fms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {
    private String id;
    private String fileName;
    private String originalFileName;
    private String contentType;
    private long size;
    private String path;
    private String thumbnailPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean isImage;
    private String provider;
}