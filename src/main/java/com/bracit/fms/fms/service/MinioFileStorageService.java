package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "minio")
public class MinioFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final ConcurrentHashMap<String, FileInfo> fileMetadata = new ConcurrentHashMap<>();
    private MinioClient minioClient;

    @PostConstruct
    public void initializeMinioClient() {
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
                .build();

        // Create bucket if it doesn't exist
        try {
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .build());

            if (!bucketExists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(properties.getMinio().getBucketName())
                        .build());
                log.info("Created MinIO bucket: {}", properties.getMinio().getBucketName());
            }
        } catch (Exception e) {
            log.error("Error initializing MinIO bucket: {}", e.getMessage());
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
                        uploadToMinio(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = "files/" + fileName;
            uploadToMinio(fileKey, content, request.getContentType());

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
                    .provider("minio")
                    .build();

            fileMetadata.put(fileId, fileInfo);
            log.info("File uploaded to MinIO successfully: {}", fileId);

            return fileInfo;
        } catch (Exception e) {
            log.error("Error uploading file to MinIO: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return Optional.empty();
        }

        try {
            GetObjectArgs getObjectArgs = GetObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(fileInfo.getPath())
                    .build();

            InputStream stream = minioClient.getObject(getObjectArgs);
            byte[] content = stream.readAllBytes();
            stream.close();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error downloading file from MinIO {}: {}", fileId, e.getMessage());
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
            RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(fileInfo.getPath())
                    .build();

            minioClient.removeObject(removeObjectArgs);

            // Delete thumbnail if exists
            if (fileInfo.getThumbnailPath() != null) {
                RemoveObjectArgs thumbnailRemoveArgs = RemoveObjectArgs.builder()
                        .bucket(properties.getMinio().getBucketName())
                        .object(fileInfo.getThumbnailPath())
                        .build();
                minioClient.removeObject(thumbnailRemoveArgs);
            }

            fileMetadata.remove(fileId);
            log.info("File deleted from MinIO successfully: {}", fileId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting file from MinIO {}: {}", fileId, e.getMessage());
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
            GetObjectArgs getObjectArgs = GetObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(fileInfo.getThumbnailPath())
                    .build();

            InputStream stream = minioClient.getObject(getObjectArgs);
            byte[] content = stream.readAllBytes();
            stream.close();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error getting thumbnail from MinIO for file {}: {}", fileId, e.getMessage());
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
            StatObjectArgs statObjectArgs = StatObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(fileInfo.getPath())
                    .build();

            minioClient.statObject(statObjectArgs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "minio";
    }

    private void uploadToMinio(String objectName, byte[] content, String contentType) throws Exception {
        PutObjectArgs putObjectArgs = PutObjectArgs.builder()
                .bucket(properties.getMinio().getBucketName())
                .object(objectName)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType(contentType)
                .build();

        minioClient.putObject(putObjectArgs);
    }

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}
