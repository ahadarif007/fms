package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.entity.FileEntity;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import com.bracit.fms.fms.repository.FileRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "minio")
public class MinioFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final FileRepository fileRepository;
    private MinioClient minioClient;

    @PostConstruct
    public void initializeMinioClient() {
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getMinio().getEndpoint())
                .credentials(properties.getMinio().getAccessKey(), properties.getMinio().getSecretKey())
                .build();

        log.info("MinIO client initialized successfully");
    }

    @Override
    public FileInfo uploadFile(FileUploadRequest request) {
        try {
            String bucketName = request.getFileType().toLowerCase().replace("_", "-");
            // Create bucket if it doesn't exist
            boolean bucketExists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName)
                    .build());

            if (!bucketExists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName)
                        .build());
                log.info("Created MinIO bucket: {}", bucketName);
            }

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
                        uploadToMinio(bucketName, thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = "files/" + fileName;
            uploadToMinio(bucketName, fileKey, content, request.getContentType());

            FileEntity fileEntity = FileEntity.builder()
                    .id(fileId)
                    .fileName(fileName)
                    .originalFileName(request.getFileName())
                    .contentType(request.getContentType()).size((long) content.length)
                    .path(fileKey)
                    .thumbnailPath(thumbnailPath)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .isImage(isImage)
                    .provider("minio").fileType(request.getFileType())
                    .build();

            fileRepository.save(fileEntity);

            return convertToFileInfo(fileEntity);
        } catch (Exception e) {
            log.error("Error uploading file to MinIO: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        Optional<FileEntity> fileEntity = fileRepository.findById(fileId);
        if (fileEntity.isEmpty()) {
            return Optional.empty();
        }

        FileEntity entity = fileEntity.get();
        String bucketName = entity.getFileType().toLowerCase().replace("_", "-");
        try {
            GetObjectArgs getObjectArgs = GetObjectArgs.builder().bucket(bucketName).object(entity.getPath())
                    .build();

            InputStream stream = minioClient.getObject(getObjectArgs);
            byte[] content = stream.readAllBytes();
            stream.close();

            return Optional.of(FileDownloadResponse.builder().fileName(entity.getOriginalFileName()).contentType(entity.getContentType())
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
        String bucketName = entity.getFileType().toLowerCase().replace("_", "-");

        try {
            // Delete main file
            RemoveObjectArgs removeObjectArgs = RemoveObjectArgs.builder().bucket(bucketName).object(entity.getPath())
                    .build();

            minioClient.removeObject(removeObjectArgs);

            // Delete thumbnail if exists
            if (entity.getThumbnailPath() != null) {
                RemoveObjectArgs thumbnailRemoveArgs = RemoveObjectArgs.builder().bucket(bucketName).object(entity.getThumbnailPath())
                        .build();
                minioClient.removeObject(thumbnailRemoveArgs);
            }

            fileRepository.deleteById(fileId);
            log.info("File deleted from MinIO successfully: {}", fileId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting file from MinIO {}: {}", fileId, e.getMessage());
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
        String bucketName = entity.getFileType().toLowerCase().replace("_", "-");
        try {
            GetObjectArgs getObjectArgs = GetObjectArgs.builder().bucket(bucketName).object(entity.getThumbnailPath())
                    .build();

            InputStream stream = minioClient.getObject(getObjectArgs);
            byte[] content = stream.readAllBytes();
            stream.close();

            return Optional.of(ThumbnailResponse.builder().fileName("thumb_" + entity.getOriginalFileName()).contentType(entity.getContentType())
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
        return fileRepository.findAll().stream().map(this::convertToFileInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean fileExists(String fileId) {
        return fileRepository.existsByIdAndProvider(fileId, "minio");
    }

    @Override
    public String getProviderName() {
        return "minio";
    }

    private void uploadToMinio(String bucketName, String objectName, byte[] content, String contentType) throws Exception {
        PutObjectArgs putObjectArgs = PutObjectArgs.builder().bucket(bucketName)
                .object(objectName)
                .stream(new ByteArrayInputStream(content), content.length, -1)
                .contentType(contentType)
                .build();

        minioClient.putObject(putObjectArgs);
    }

    private FileInfo convertToFileInfo(FileEntity entity) {
        return FileInfo.builder().id(entity.getId()).fileName(entity.getFileName()).originalFileName(entity.getOriginalFileName()).contentType(entity.getContentType()).size(entity.getSize()).path(entity.getPath()).thumbnailPath(entity.getThumbnailPath()).createdAt(entity.getCreatedAt()).updatedAt(entity.getUpdatedAt()).isImage(entity.getIsImage()).provider(entity.getProvider()).fileType(entity.getFileType()).build();
    }
    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}
