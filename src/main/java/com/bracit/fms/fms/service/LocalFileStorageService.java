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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file.storage.provider", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private final FileStorageProperties properties;
    private final FileRepository fileRepository;
    private final ImageProcessingService imageProcessingService;

    @Override
    public FileInfo uploadFile(FileUploadRequest request) {
        try {
            String fileId = UUID.randomUUID().toString();
            String fileName = generateFileName(fileId, request.getFileName());
            Path basePath = Paths.get(properties.getLocal().getBasePath(), request.getFileType());

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

            FileEntity fileEntity = FileEntity.builder()
                    .id(fileId)
                    .fileName(fileName)
                    .originalFileName(request.getFileName())
                    .contentType(request.getContentType())
                    .size((long) content.length)
                    .path(filePath.toString())
                    .thumbnailPath(thumbnailPath)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .isImage(isImage)
                    .provider("local")
                    .fileType(request.getFileType())
                    .build();

            fileRepository.save(fileEntity);

            return convertToFileInfo(fileEntity);
        } catch (IOException e) {
            log.error("Error uploading file: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file", e);
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
            Path filePath = Paths.get(entity.getPath());
            if (!Files.exists(filePath)) {
                return Optional.empty();
            }

            byte[] content = Files.readAllBytes(filePath);

            return Optional.of(FileDownloadResponse.builder()
                    .fileName(entity.getOriginalFileName())
                    .contentType(entity.getContentType())
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
            Path filePath = Paths.get(entity.getPath());
            Files.deleteIfExists(filePath);

            // Delete thumbnail if exists
            if (entity.getThumbnailPath() != null) {
                Path thumbnailPath = Paths.get(entity.getThumbnailPath());
                Files.deleteIfExists(thumbnailPath);
            }

            fileRepository.deleteById(fileId);
            log.info("File deleted successfully: {}", fileId);
            return true;
        } catch (IOException e) {
            log.error("Error deleting file {}: {}", fileId, e.getMessage());
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
            Path thumbnailPath = Paths.get(entity.getThumbnailPath());
            if (!Files.exists(thumbnailPath)) {
                return Optional.empty();
            }

            byte[] content = Files.readAllBytes(thumbnailPath);

            return Optional.of(ThumbnailResponse.builder()
                    .fileName("thumb_" + entity.getOriginalFileName())
                    .contentType(entity.getContentType())
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
        return fileRepository.findAll().stream()
                .map(this::convertToFileInfo)
                .collect(Collectors.toList());
    }

    @Override
    public boolean fileExists(String fileId) {
        return fileRepository.existsByIdAndProvider(fileId, "local");
    }

    @Override
    public String getProviderName() {
        return "local";
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
