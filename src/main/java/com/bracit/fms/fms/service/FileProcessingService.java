package com.bracit.fms.fms.service;

import java.util.List;
import java.util.Map;

/**
 * Unified service interface for file processing operations including compression, decompression, and image processing
 */
public interface FileProcessingService {

    // ========== COMPRESSION OPERATIONS ==========

    /**
     * Compress file based on its content type and characteristics
     *
     * @param fileData    The original file data as byte array
     * @param contentType MIME type of the file
     * @param fileName    Name of the file (used for logging and optimization)
     * @return CompressionResult containing compressed data and metadata
     */
    CompressionResult compressFile(byte[] fileData, String contentType, String fileName);

    /**
     * Decompress file based on compression method used
     *
     * @param compressedData    The compressed file data
     * @param compressionMethod The compression method that was used (e.g., "GZIP", "PDF_OPTIMIZATION")
     * @return Decompressed file data as byte array
     */
    byte[] decompressFile(byte[] compressedData, String compressionMethod);

    /**
     * Get compression recommendations for a specific file type and size
     *
     * @param contentType MIME type of the file
     * @param fileSize    Size of the file in bytes
     * @return CompressionRecommendation with analysis and suggestions
     */
    CompressionRecommendation getCompressionRecommendation(String contentType, long fileSize);

    /**
     * Check if a file type is suitable for compression
     *
     * @param contentType MIME type of the file
     * @return true if compression is recommended for this file type
     */
    default boolean isCompressionRecommended(String contentType) {
        return getCompressionRecommendation(contentType, 0).isRecommended();
    }

    /**
     * Get estimated compression savings for a file type
     *
     * @param contentType MIME type of the file
     * @return Expected compression ratio (0.0 to 1.0)
     */
    default double getEstimatedCompressionRatio(String contentType) {
        return getCompressionRecommendation(contentType, 0).getExpectedSavings();
    }

    // ========== IMAGE PROCESSING OPERATIONS ==========

    /**
     * Check if content type represents an image file
     *
     * @param contentType MIME type to check
     * @return true if content type is an image
     */
    boolean isImageFile(String contentType);

    /**
     * Check if image format is supported for processing
     *
     * @param contentType MIME type of the image
     * @return true if image format is supported
     */
    boolean isSupportedImageFormat(String contentType);

    /**
     * Compress image with intelligent format-specific optimization
     *
     * @param imageData   The original image data
     * @param contentType MIME type of the image
     * @return Compressed image data, or original if compression not beneficial
     */
    byte[] compressImage(byte[] imageData, String contentType);

    /**
     * Generate thumbnail with aspect ratio preservation and smart cropping
     *
     * @param imageData   The original image data
     * @param contentType MIME type of the image
     * @return Thumbnail data, or null if generation failed
     */
    byte[] generateThumbnail(byte[] imageData, String contentType);

    /**
     * Generate multiple thumbnail sizes
     *
     * @param imageData   The original image data
     * @param contentType MIME type of the image
     * @param sizes       Array of thumbnail sizes to generate
     * @return List of thumbnail data for each size
     */
    List<byte[]> generateMultipleThumbnails(byte[] imageData, String contentType, int... sizes);

    /**
     * Get image metadata without full processing
     *
     * @param imageData The image data to analyze
     * @return ImageMetadata containing dimensions and properties
     */
    ImageMetadata getImageMetadata(byte[] imageData);

    // ========== COMBINED OPERATIONS ==========

    /**
     * Process file with both compression and image optimization if applicable
     *
     * @param fileData    The original file data
     * @param contentType MIME type of the file
     * @param fileName    Name of the file
     * @return ProcessingResult containing all processing outcomes
     */
    ProcessingResult processFile(byte[] fileData, String contentType, String fileName);

    /**
     * Process file with custom options
     *
     * @param fileData    The original file data
     * @param contentType MIME type of the file
     * @param fileName    Name of the file
     * @param options     Processing options
     * @return ProcessingResult containing all processing outcomes
     */
    ProcessingResult processFile(byte[] fileData, String contentType, String fileName, ProcessingOptions options);

    // ========== RESULT INTERFACES ==========

    /**
     * Result object containing compression operation details
     */
    interface CompressionResult {
        byte[] getCompressedData();

        long getOriginalSize();

        long getCompressedSize();

        double getCompressionRatio();

        String getMethod();

        boolean isCompressed();

        String getReason();

        Map<String, String> getMetadata();

        long getSavedBytes();
    }

    /**
     * Recommendation object for compression analysis
     */
    interface CompressionRecommendation {
        boolean isRecommended();

        String getReason();

        double getExpectedSavings();

        String getMethod();
    }

    /**
     * Image metadata container
     */
    interface ImageMetadata {
        int getWidth();

        int getHeight();

        long getSizeInBytes();

        boolean hasAlpha();

        double getAspectRatio();

        long getPixelCount();
    }

    /**
     * Combined processing result
     */
    interface ProcessingResult {
        byte[] getProcessedData();

        byte[] getThumbnailData();

        CompressionResult getCompressionResult();

        ImageMetadata getImageMetadata();

        boolean wasProcessed();

        String getProcessingMethod();

        Map<String, Object> getProcessingMetadata();

        long getTotalSavedBytes();
    }

    /**
     * Processing options configuration
     */
    interface ProcessingOptions {
        boolean isCompressionEnabled();

        boolean isImageOptimizationEnabled();

        boolean isThumbnailGenerationEnabled();

        int getThumbnailSize();

        float getImageQuality();

        static ProcessingOptions defaultOptions() {
            return ProcessingOptionsImpl.builder()
                    .compressionEnabled(true)
                    .imageOptimizationEnabled(true)
                    .thumbnailGenerationEnabled(true)
                    .thumbnailSize(150)
                    .imageQuality(0.8f)
                    .build();
        }
    }

    // ========== BUILDER CLASSES ==========

    @lombok.Builder
    @lombok.Data
    class ProcessingOptionsImpl implements ProcessingOptions {
        private final boolean compressionEnabled;
        private final boolean imageOptimizationEnabled;
        private final boolean thumbnailGenerationEnabled;
        private final int thumbnailSize;
        private final float imageQuality;
    }
}