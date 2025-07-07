package com.bracit.fms.fms.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
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

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "azure")
public class AzureFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final FileRepository fileRepository;
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
                        String thumbnailKey = request.getFileType() + "/thumbnails/" + fileId + "_thumb";
                        uploadToAzure(thumbnailKey, thumbnailData, request.getContentType());
                        thumbnailPath = thumbnailKey;
                    }
                }
            }

            // Upload main file
            String fileKey = request.getFileType() + "/files/" + fileName;
            uploadToAzure(fileKey, content, request.getContentType());

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
                    .provider("azure").fileType(request.getFileType())
                    .build();

            fileRepository.save(fileEntity);
            log.info("File uploaded to Azure successfully: {}", fileId);

            return convertToFileInfo(fileEntity);
        } catch (Exception e) {
            log.error("Error uploading file to Azure: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to Azure", e);
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
            BlobClient blobClient = containerClient.getBlobClient(entity.getPath());

            if (!blobClient.exists()) {
                return Optional.empty();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] content = outputStream.toByteArray();

            return Optional.of(FileDownloadResponse.builder().fileName(entity.getOriginalFileName()).contentType(entity.getContentType())
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
            BlobClient blobClient = containerClient.getBlobClient(entity.getPath());
            boolean deleted = blobClient.deleteIfExists();

            // Delete thumbnail if exists
            if (entity.getThumbnailPath() != null) {
                BlobClient thumbnailBlobClient = containerClient.getBlobClient(entity.getThumbnailPath());
                thumbnailBlobClient.deleteIfExists();
            }

            if (deleted) {
                fileRepository.deleteById(fileId);
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
        Optional<FileEntity> entityOpt = fileRepository.findById(fileId);
        if (entityOpt.isEmpty() || entityOpt.get().getThumbnailPath() == null) {
            return Optional.empty();
        }

        FileEntity entity = entityOpt.get();
        try {
            BlobClient blobClient = containerClient.getBlobClient(entity.getThumbnailPath());

            if (!blobClient.exists()) {
                return Optional.empty();
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] content = outputStream.toByteArray();

            return Optional.of(ThumbnailResponse.builder().fileName("thumb_" + entity.getOriginalFileName()).contentType(entity.getContentType())
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
        return fileRepository.findAll().stream().map(this::convertToFileInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean fileExists(String fileId) {
        return fileRepository.existsByIdAndProvider(fileId, "azure");
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