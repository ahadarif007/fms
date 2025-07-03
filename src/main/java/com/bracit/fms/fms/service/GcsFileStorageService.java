package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import com.google.cloud.storage.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "gcs")
public class GcsFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final ConcurrentHashMap<String, FileInfo> fileMetadata = new ConcurrentHashMap<>();
    private Storage storage;

    @PostConstruct
    public void initializeGcsClient() {
        try {
            StorageOptions.Builder builder = StorageOptions.newBuilder()
                    .setProjectId(properties.getGcs().getProjectId());

            // If credentials path is provided, use it
            if (properties.getGcs().getCredentialsPath() != null) {
                builder.setCredentials(
                        com.google.auth.oauth2.ServiceAccountCredentials.fromStream(
                                new FileInputStream(properties.getGcs().getCredentialsPath())
                        )
                );
            }

            this.storage = builder.build().getService();
            log.info("GCS client initialized successfully");
        } catch (IOException e) {
            log.error("Error initializing GCS client: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize GCS client", e);
        }
    }

    @Override
    public FileInfo uploadFile(FileUploadRequest request) {
        try {
            String fileId = UUID.randomUUID().toString();
            String fileName = generateFileName(fileId, request.getFileName());
            byte[] content = request.getContent();

            // Process image if needed
            boolean isImage = imageProcessingService.isImageFile(request.getContentType());
            String thumbnailPath = null;

            if (isImage && imageProcessingService.isSupportedImageFormat(request.getContentType())) {
                if (request.isCompressImage()) {
                    content = imageProcessingService.compressImage(content, request.getContentType());
                }

                if (request.isGenerateThumbnail()) {
                    byte[] thumbnailData = imageProcessingService.generateThumbnail(content, request.getContentType());
                    if (thumbnailData != null) {
                        String thumbnailKey = "thumbnails/" + fileId + "_thumb";
                        uploadToGcs(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = "files/" + fileName;
            uploadToGcs(fileKey, content, request.getContentType());

            FileInfo fileInfo = FileInfo.builder()
                    .id(fileId)
                    .fileName(fileName)
                    .originalFileName(request.getFileName())
                    .contentType(request.getContentType())
                    .size(content.length)
                    .path(fileKey)
                    .thumbnailPath(thumbnailPath)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .isImage(isImage)
                    .provider("gcs")
                    .build();

            fileMetadata.put(fileId, fileInfo);
            log.info("File uploaded to GCS successfully: {}", fileId);

            return fileInfo;
        } catch (Exception e) {
            log.error("Error uploading file to GCS: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to GCS", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return Optional.empty();
        }

        try {
            Blob blob = storage.get(properties.getGcs().getBucketName(), fileInfo.getPath());
            if (blob == null) {
                return Optional.empty();
            }

            byte[] content = blob.getContent();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error downloading file from GCS {}: {}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<FileInfo> getFileInfo(String fileId) {
        return Optional.ofNullable(fileMetadata.get(fileId));
    }

    @Override
    public Optional<FileInfo> updateFileInfo(String fileId, FileInfo updatedFileInfo) {
        FileInfo existingFileInfo = fileMetadata.get(fileId);
        if (existingFileInfo == null) {
            return Optional.empty();
        }

        existingFileInfo.setOriginalFileName(updatedFileInfo.getOriginalFileName());
        existingFileInfo.setUpdatedAt(LocalDateTime.now());

        fileMetadata.put(fileId, existingFileInfo);
        return Optional.of(existingFileInfo);
    }

    @Override
    public boolean deleteFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return false;
        }

        try {
            // Delete main file
            boolean deleted = storage.delete(properties.getGcs().getBucketName(), fileInfo.getPath());

            // Delete thumbnail if exists
            if (fileInfo.getThumbnailPath() != null) {
                storage.delete(properties.getGcs().getBucketName(), fileInfo.getThumbnailPath());
            }

            if (deleted) {
                fileMetadata.remove(fileId);
                log.info("File deleted from GCS successfully: {}", fileId);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Error deleting file from GCS {}: {}", fileId, e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<ThumbnailResponse> getThumbnail(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null || fileInfo.getThumbnailPath() == null) {
            return Optional.empty();
        }

        try {
            Blob blob = storage.get(properties.getGcs().getBucketName(), fileInfo.getThumbnailPath());
            if (blob == null) {
                return Optional.empty();
            }

            byte[] content = blob.getContent();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error getting thumbnail from GCS for file {}: {}", fileId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<FileInfo> listFiles() {
        return fileMetadata.values().stream()
                .collect(Collectors.toList());
    }

    @Override
    public boolean fileExists(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return false;
        }

        try {
            Blob blob = storage.get(properties.getGcs().getBucketName(), fileInfo.getPath());
            return blob != null;
        } catch (Exception e) {
            log.error("Error checking if file exists in GCS {}: {}", fileId, e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "gcs";
    }

    private void uploadToGcs(String objectName, byte[] content, String contentType) {
        BlobId blobId = BlobId.of(properties.getGcs().getBucketName(), objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();

        storage.create(blobInfo, content);
    }

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}