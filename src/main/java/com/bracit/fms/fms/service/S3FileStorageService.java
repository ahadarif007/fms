package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
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
    private final ConcurrentHashMap<String, FileInfo> fileMetadata = new ConcurrentHashMap<>();
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
                        String thumbnailKey = "thumbnails/" + fileId + "_thumb";
                        uploadToS3(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = "files/" + fileName;
            uploadToS3(fileKey, content, request.getContentType());

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
                    .provider("s3")
                    .build();

            fileMetadata.put(fileId, fileInfo);
            log.info("File uploaded to S3 successfully: {}", fileId);

            return fileInfo;
        } catch (Exception e) {
            log.error("Error uploading file to S3: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to S3", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return Optional.empty();
        }

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(fileInfo.getPath())
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getObjectRequest);
            byte[] content = response.readAllBytes();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
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
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(fileInfo.getPath())
                    .build();

            s3Client.deleteObject(deleteRequest);

            // Delete thumbnail if exists
            if (fileInfo.getThumbnailPath() != null) {
                DeleteObjectRequest thumbnailDeleteRequest = DeleteObjectRequest.builder()
                        .bucket(properties.getS3().getBucketName())
                        .key(fileInfo.getThumbnailPath())
                        .build();
                s3Client.deleteObject(thumbnailDeleteRequest);
            }

            fileMetadata.remove(fileId);
            log.info("File deleted from S3 successfully: {}", fileId);
            return true;
        } catch (Exception e) {
            log.error("Error deleting file from S3 {}: {}", fileId, e.getMessage());
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
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(fileInfo.getThumbnailPath())
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getObjectRequest);
            byte[] content = response.readAllBytes();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
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
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(properties.getS3().getBucketName())
                    .key(fileInfo.getPath())
                    .build();

            s3Client.headObject(headObjectRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("Error checking if file exists in S3 {}: {}", fileId, e.getMessage());
            return false;
        }
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

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}