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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final ImageProcessingService imageProcessingService;
    private final ConcurrentHashMap<String, FileInfo> fileMetadata = new ConcurrentHashMap<>();

    @Override
    public FileInfo uploadFile(FileUploadRequest request) {
        try {
            String fileId = UUID.randomUUID().toString();
            String fileName = generateFileName(fileId, request.getFileName());
            Path basePath = Paths.get(properties.getLocal().getBasePath());

            // Create directory if it doesn't exist
            Files.createDirectories(basePath);

            Path filePath = basePath.resolve(fileName);
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
                        String thumbnailFileName = "thumb_" + fileName;
                        Path thumbnailFilePath = basePath.resolve(thumbnailFileName);
                        Files.write(thumbnailFilePath, thumbnailData);
                        thumbnailPath = thumbnailFilePath.toString();
                    }
                }
            }

            // Save main file
            Files.write(filePath, content);

            FileInfo fileInfo = FileInfo.builder()
                    .id(fileId)
                    .fileName(fileName)
                    .originalFileName(request.getFileName())
                    .contentType(request.getContentType())
                    .size(content.length)
                    .path(filePath.toString())
                    .thumbnailPath(thumbnailPath)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .isImage(isImage)
                    .provider("local")
                    .build();

            fileMetadata.put(fileId, fileInfo);
            log.info("File uploaded successfully: {}", fileId);

            return fileInfo;
        } catch (IOException e) {
            log.error("Error uploading file: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file", e);
        }
    }

    @Override
    public Optional<FileDownloadResponse> downloadFile(String fileId) {
        FileInfo fileInfo = fileMetadata.get(fileId);
        if (fileInfo == null) {
            return Optional.empty();
        }

        try {
            Path filePath = Paths.get(fileInfo.getPath());
            if (!Files.exists(filePath)) {
                return Optional.empty();
            }

            byte[] content = Files.readAllBytes(filePath);

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (IOException e) {
            log.error("Error downloading file {}: {}", fileId, e.getMessage());
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
            Path filePath = Paths.get(fileInfo.getPath());
            Files.deleteIfExists(filePath);

            // Delete thumbnail if exists
            if (fileInfo.getThumbnailPath() != null) {
                Path thumbnailPath = Paths.get(fileInfo.getThumbnailPath());
                Files.deleteIfExists(thumbnailPath);
            }

            fileMetadata.remove(fileId);
            log.info("File deleted successfully: {}", fileId);
            return true;
        } catch (IOException e) {
            log.error("Error deleting file {}: {}", fileId, e.getMessage());
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
            Path thumbnailPath = Paths.get(fileInfo.getThumbnailPath());
            if (!Files.exists(thumbnailPath)) {
                return Optional.empty();
            }

            byte[] content = Files.readAllBytes(thumbnailPath);

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + fileInfo.getOriginalFileName())
                    .contentType(fileInfo.getContentType())
                    .content(content)
                    .size(content.length)
                    .build());
        } catch (IOException e) {
            log.error("Error getting thumbnail for file {}: {}", fileId, e.getMessage());
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

        Path filePath = Paths.get(fileInfo.getPath());
        return Files.exists(filePath);
    }

    @Override
    public String getProviderName() {
        return "local";
    }

    private String generateFileName(String fileId, String originalFileName) {
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        return fileId + extension;
    }
}
