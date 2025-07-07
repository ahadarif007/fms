package com.bracit.fms.fms.service;

import java.util.Map;

/**
 * Service interface for file compression and decompression operations
 */
public interface FileCompressionService {

    /**
     * Compress file based on its content type and characteristics
     *
     * @param fileData The original file data as byte array
     * @param contentType MIME type of the file
     * @param fileName Name of the file (used for logging and optimization)
     * @return CompressionResult containing compressed data and metadata
     */
    CompressionResult compressFile(byte[] fileData, String contentType, String fileName);

    /**
     * Decompress file based on compression method used
     *
     * @param compressedData The compressed file data
     * @param compressionMethod The compression method that was used (e.g., "GZIP", "PDF_OPTIMIZATION")
     * @return Decompressed file data as byte array
     */
    byte[] decompressFile(byte[] compressedData, String compressionMethod);

    /**
     * Get compression recommendations for a specific file type and size
     *
     * @param contentType MIME type of the file
     * @param fileSize Size of the file in bytes
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

        static CompressionResult noCompression(String reason) {
            return FileCompressionServiceImpl.CompressionResultImpl.noCompression(reason);
        }
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
}