package com.bracit.fms.fms.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.bracit.fms.fms.config.FileStorageProperties;
import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "azure")
public class AzureFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final ConcurrentHashMap<String, FileInfo> fileMetadata = new ConcurrentHashMap<>();
    private BlobServiceClient blobServiceClient;
    private BlobContainerClient containerClient;

    @PostConstruct
    public void initializeAzureClient() {
        this.blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(properties.getAzure().getConnectionString())
                .buildClient();

        this.containerClient = blobServiceClient.getBlobContainerClient(
                properties.getAzure().getContainerName()
        );

        // Create container if it doesn't exist
        if (!containerClient.exists()) {
            containerClient.create();
            log.info("Created Azure container: {}", properties.getAzure().getContainerName());
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
                        uploadToAzure(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = "files/" + fileName;
            uploadToAzure(fileKey, content, request.getContentType());

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
                    .provider("azure")
                    .build();

            fileMetadata.put(fileId, fileInfo);
            log.info("File uploaded to Azure successfully: {}", fileId);

            return fileInfo;
        } catch (Exception e) {
            log.error("Error uploading file to Azure: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to Azure", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return Optional.empty();
        }

        try {
            BlobClient blobClient = containerClient.getBlobClient(fileInfo.getPath());

            if (!blobClient.exists()) {
                return Optional.empty();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] content = outputStream.toByteArray();

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error downloading file from Azure {}: {}", fileId, e.getMessage());
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
            BlobClient blobClient = containerClient.getBlobClient(fileInfo.getPath());
            boolean deleted = blobClient.deleteIfExists();

            // Delete thumbnail if exists
            if (fileInfo.getThumbnailPath() != null) {
                BlobClient thumbnailBlobClient = containerClient.getBlobClient(fileInfo.getThumbnailPath());
                thumbnailBlobClient.deleteIfExists();
            }

            if (deleted) {
                fileMetadata.remove(fileId);
                log.info("File deleted from Azure successfully: {}", fileId);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.error("Error deleting file from Azure {}: {}", fileId, e.getMessage());
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
            BlobClient blobClient = containerClient.getBlobClient(fileInfo.getThumbnailPath());

            if (!blobClient.exists()) {
                return Optional.empty();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] content = outputStream.toByteArray();

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (Exception e) {
            log.error("Error getting thumbnail from Azure for file {}: {}", fileId, e.getMessage());
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
            BlobClient blobClient = containerClient.getBlobClient(fileInfo.getPath());
            return blobClient.exists();
        } catch (Exception e) {
            log.error("Error checking if file exists in Azure {}: {}", fileId, e.getMessage());
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "azure";
    }

    private void uploadToAzure(String blobName, byte[] content, String contentType) {
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(content);
        blobClient.upload(inputStream, content.length, true);

        // Set content type
        blobClient.setHttpHeaders(new com.azure.storage.blob.models.BlobHttpHeaders()
                .setContentType(contentType));
    }

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}