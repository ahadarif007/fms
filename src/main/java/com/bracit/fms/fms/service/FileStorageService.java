package com.bracit.fms.fms.service;

import com.bracit.fms.fms.model.FileDownloadResponse;
import com.bracit.fms.fms.model.FileInfo;
import com.bracit.fms.fms.model.FileUploadRequest;
import com.bracit.fms.fms.model.ThumbnailResponse;

import java.util.List;
import java.util.Optional;

public interface FileStorageService {

    /**
     * Upload a file to the storage provider
     *
     * @param request File upload request containing file data
     * @return FileInfo with metadata about the uploaded file
     */
    FileInfo uploadFile(FileUploadRequest request);

    /**
     * Download a file from the storage provider
     *
     * @param fileId Unique identifier of the file
     * @return FileDownloadResponse containing file data
     */
    Optional<FileDownloadResponse> downloadFile(String fileId);

    /**
     * Get file metadata by ID
     *
     * @param fileId Unique identifier of the file
     * @return FileInfo with metadata
     */
    Optional<FileInfo> getFileInfo(String fileId);

    /**
     * Update file metadata
     *
     * @param fileId   Unique identifier of the file
     * @param fileInfo Updated file information
     * @return Updated FileInfo
     */
    Optional<FileInfo> updateFileInfo(String fileId, FileInfo fileInfo);

    /**
     * Delete a file from the storage provider
     *
     * @param fileId Unique identifier of the file
     * @return true if deleted successfully, false otherwise
     */
    boolean deleteFile(String fileId);

    /**
     * Get thumbnail for an image file
     *
     * @param fileId Unique identifier of the file
     * @return ThumbnailResponse containing thumbnail data
     */
    Optional<ThumbnailResponse> getThumbnail(String fileId);

    /**
     * List all files
     *
     * @return List of FileInfo objects
     */
    List<FileInfo> listFiles();

    /**
     * Check if file exists
     *
     * @param fileId Unique identifier of the file
     * @return true if file exists, false otherwise
     */
    boolean fileExists(String fileId);

    /**
     * Get storage provider name
     *
     * @return Name of the current storage provider
     */
    String getProviderName();
}