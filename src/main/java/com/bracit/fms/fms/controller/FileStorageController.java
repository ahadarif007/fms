package com.bracit.fms.fms.controller;

import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;
import com.bracit.fms.fms.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileStorageController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public ResponseEntity<FileInfo> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "compressImage", defaultValue = "true") boolean compressImage,
            @RequestParam(value = "generateThumbnail", defaultValue = "true") boolean generateThumbnail) {

        try {
            FileUploadRequest request = FileUploadRequest.builder()
                    .fileName(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .content(file.getBytes())
                    .compressImage(compressImage)
                    .generateThumbnail(generateThumbnail)
                    .build();

            FileInfo fileInfo = fileStorageService.uploadFile(request);
            return ResponseEntity.ok(fileInfo);
        } catch (IOException e) {
            log.error("Error processing uploaded file: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String fileId) {
        Optional<FileDownloadResponse> response = fileStorageService.downloadFile(fileId);

        if (response.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        FileDownloadResponse fileResponse = response.get();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(fileResponse.getContentType()));
        headers.setContentDispositionFormData("attachment", fileResponse.getFileName());
        headers.setContentLength(fileResponse.getSize());

        return ResponseEntity.ok()
                .headers(headers)
                .body(fileResponse.getContent());
    }

    @GetMapping("/{fileId}/info")
    public ResponseEntity<FileInfo> getFileInfo(@PathVariable String fileId) {
        Optional<FileInfo> fileInfo = fileStorageService.getFileInfo(fileId);

        return fileInfo.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{fileId}")
    public ResponseEntity<FileInfo> updateFileInfo(@PathVariable String fileId, @RequestBody FileInfo fileInfo) {
        Optional<FileInfo> updatedFileInfo = fileStorageService.updateFileInfo(fileId, fileInfo);

        return updatedFileInfo.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> deleteFile(@PathVariable String fileId) {
        boolean deleted = fileStorageService.deleteFile(fileId);

        if (deleted) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{fileId}/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable String fileId) {
        Optional<ThumbnailResponse> response = fileStorageService.getThumbnail(fileId);

        if (response.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ThumbnailResponse thumbnailResponse = response.get();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(thumbnailResponse.getContentType()));
        headers.setContentLength(thumbnailResponse.getSize());

        return ResponseEntity.ok()
                .headers(headers)
                .body(thumbnailResponse.getContent());
    }

    @GetMapping("/list")
    public ResponseEntity<List<FileInfo>> listFiles() {
        List<FileInfo> files = fileStorageService.listFiles();
        return ResponseEntity.ok(files);
    }

    @GetMapping("/{fileId}/exists")
    public ResponseEntity<Boolean> fileExists(@PathVariable String fileId) {
        boolean exists = fileStorageService.fileExists(fileId);
        return ResponseEntity.ok(exists);
    }

    @GetMapping("/provider")
    public ResponseEntity<String> getProviderName() {
        String providerName = fileStorageService.getProviderName();
        return ResponseEntity.ok(providerName);
    }
}