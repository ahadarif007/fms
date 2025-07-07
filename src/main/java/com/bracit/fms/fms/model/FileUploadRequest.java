package com.bracit.fms.fms.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadRequest {
    private String fileName;
    private String contentType;
    private byte[] content;
    private boolean generateThumbnail;
    private boolean compressImage;
    private String fileType; // e.g., "profile_picture", "NID", "documents"
}