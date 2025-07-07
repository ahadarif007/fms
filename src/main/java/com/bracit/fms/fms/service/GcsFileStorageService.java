package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.entity.FileEntity;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import com.bracit.fms.fms.repository.FileRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "gcs")
public class GcsFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final FileRepository fileRepository;
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

    private void uploadToGcs(String objectName, byte[] content, String contentType) {
        BlobId blobId = BlobId.of(properties.getGcs().getBucketName(), objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();

        storage.create(blobInfo, content);
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
                        String thumbnailKey = request.getFileType() + "/thumbnails/" + fileId + "_thumb";
                        uploadToGcs(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = request.getFileType() + "/files/" + fileName;
            uploadToGcs(fileKey, content, request.getContentType());

            FileEntity fileEntity = FileEntity.builder()
                    .id(fileId)
                    .fileName(fileName)
                    .originalFileName(request.getFileName())
                    .contentType(request.getContentType())
                    .size((long) content.length)
                    .path(fileKey)
                    .thumbnailPath(thumbnailPath)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .isImage(isImage)
                    .provider("gcs")
                    .fileType(request.getFileType())
                    .build();

            fileRepository.save(fileEntity);

            return convertToFileInfo(fileEntity);
        } catch (Exception e) {
            log.error("Error uploading file to GCS: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to GCS", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        Optional<FileEntity> fileEntity = fileRepository.findById(fileId);
        if (fileEntity.isEmpty()) {
            return Optional.empty();
        }

        FileEntity entity = fileEntity.get();
        try {
            Blob blob = storage.get(properties.getGcs().getBucketName(), entity.getPath());
            if (blob == null) {
                return Optional.empty();
            }

            byte[] content = blob.getContent();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(entity.getOriginalFileName())
                    .contentType(entity.getContentType())
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
        return fileRepository.findById(fileId).map(this::convertToFileInfo);
    }

    @Override
    public Optional<FileInfo> updateFileInfo(String fileId, FileInfo updatedFileInfo) {
        Optional<FileEntity> entityOpt = fileRepository.findById(fileId);
        if (entityOpt.isEmpty()) {
            return Optional.empty();
        }

        FileEntity entity = entityOpt.get();
        entity.setOriginalFileName(updatedFileInfo.getOriginalFileName());
        entity.setUpdatedAt(LocalDateTime.now());

        FileEntity saved = fileRepository.save(entity);
        return Optional.of(convertToFileInfo(saved));
    }

    @Override
    public boolean deleteFile(String fileId) {
        Optional<FileEntity> entityOpt = fileRepository.findById(fileId);
        if (entityOpt.isEmpty()) {
            return false;
        }

        FileEntity entity = entityOpt.get();
        try {
            // Delete main file
            boolean deleted = storage.delete(properties.getGcs().getBucketName(), entity.getPath());

            // Delete thumbnail if exists
            if (entity.getThumbnailPath() != null) {
                storage.delete(properties.getGcs().getBucketName(), entity.getThumbnailPath());
            }

            if (deleted) {
                fileRepository.deleteById(fileId);
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
        Optional<FileEntity> entityOpt = fileRepository.findById(fileId);
        if (entityOpt.isEmpty() || entityOpt.get().getThumbnailPath() == null) {
            return Optional.empty();
        }

        FileEntity entity = entityOpt.get();
        try {
            Blob blob = storage.get(properties.getGcs().getBucketName(), entity.getThumbnailPath());
            if (blob == null) {
                return Optional.empty();
            }

            byte[] content = blob.getContent();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + entity.getOriginalFileName())
                    .contentType(entity.getContentType())
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
        return fileRepository.findAll().stream()
                .map(this::convertToFileInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean fileExists(String fileId) {
        return fileRepository.existsByIdAndProvider(fileId, "gcs");
    }

    @Override
    public String getProviderName() {
        return "gcs";
    }

    private FileInfo convertToFileInfo(FileEntity entity) {
        return FileInfo.builder()
                .id(entity.getId())
                .fileName(entity.getFileName())
                .originalFileName(entity.getOriginalFileName())
                .contentType(entity.getContentType())
                .size(entity.getSize())
                .path(entity.getPath())
                .thumbnailPath(entity.getThumbnailPath())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .isImage(entity.getIsImage())
                .provider(entity.getProvider())
                .fileType(entity.getFileType())
                .build();

    }

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}