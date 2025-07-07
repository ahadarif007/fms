package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.entity.FileEntity;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import com.bracit.fms.fms.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "s3")
public class S3FileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final FileRepository fileRepository;
    private S3Client s3Client;

    @PostConstruct
    public void initializeS3Client() {
        AwsBasicCredentials awsCreds = AwsBasicCredentials.create(
                properties.getS3().getAccessKey(),
                properties.getS3().getSecretKey()
        );

        this.s3Client = S3Client.builder()
                .region(Region.of(properties.getS3().getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(awsCreds))
                .build();
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
                        uploadToS3(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = request.getFileType() + "/files/" + fileName;
            uploadToS3(fileKey, content, request.getContentType());

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
                    .provider("s3")
                    .fileType(request.getFileType())
                    .build();

            fileRepository.save(fileEntity);
            log.info("File uploaded to S3 successfully: {}", fileId);

            return convertToFileInfo(fileEntity);
        } catch (Exception e) {
            log.error("Error uploading file to S3: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to S3", e);
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
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(entity.getPath())
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getObjectRequest);
            byte[] content = response.readAllBytes();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(entity.getOriginalFileName())
                    .contentType(entity.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error downloading file from S3 {}: {}", fileId, e.getMessage());
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
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(entity.getPath())
                    .build();

            s3Client.deleteObject(deleteRequest);

            // Delete thumbnail if exists
            if (entity.getThumbnailPath() != null) {
                DeleteObjectRequest thumbnailDeleteRequest = DeleteObjectRequest.builder()
                        .bucket(properties.getS3().getBucketName())
                        .key(entity.getThumbnailPath())
                        .build();
                s3Client.deleteObject(thumbnailDeleteRequest);
            }

            fileRepository.deleteById(fileId);
            log.info("File deleted from S3 successfully: {}", fileId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting file from S3 {}: {}", fileId, e.getMessage());
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
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(entity.getThumbnailPath())
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getObjectRequest);
            byte[] content = response.readAllBytes();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + entity.getOriginalFileName())
                    .contentType(entity.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error getting thumbnail from S3 for file {}: {}", fileId, e.getMessage());
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
        return fileRepository.existsByIdAndProvider(fileId, "s3");
    }

    @Override
    public String getProviderName() {
        return "s3";
    }

    private void uploadToS3(String key, byte[] content, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(properties.getS3().getBucketName())
                .key(key)
                .contentType(contentType)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));
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