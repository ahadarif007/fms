package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileCompressionServiceImpl implements FileCompressionService {

    private final FileStorageProperties properties;

    // File types that compress well with general compression
    private static final Set<String> COMPRESSIBLE_TYPES = Set.of(
            "text/plain", "text/html", "text/css", "text/javascript",
            "application/json", "application/xml", "text/xml",
            "application/csv", "text/csv",
            "application/rtf", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    );

    // File types that are already compressed or don't benefit from compression
    private static final Set<String> NON_COMPRESSIBLE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "video/mp4", "video/avi", "video/mkv",
            "audio/mp3", "audio/aac", "audio/ogg",
            "application/zip", "application/rar", "application/7z",
            "application/gzip", "application/x-tar"
    );

    // PDF-specific compression
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    // Minimum compression savings threshold (15%)
    private static final double MIN_COMPRESSION_SAVINGS = 0.15;

    // Maximum file size for compression (100MB)
    private static final long MAX_COMPRESSION_SIZE = 100 * 1024 * 1024;

    /**
     * Compress file based on its content type and characteristics
     */
    @Override
    public CompressionResult compressFile(byte[] fileData, String contentType, String fileName) {
        if (fileData == null || fileData.length == 0) {
            return CompressionResult.noCompression("Empty file");
        }

        if (fileData.length > MAX_COMPRESSION_SIZE) {
            return CompressionResult.noCompression("File too large for compression");
        }

        try {
            // Check if file type is worth compressing
            if (NON_COMPRESSIBLE_TYPES.contains(contentType)) {
                return CompressionResult.noCompression("File type already compressed");
            }

            // PDF-specific compression
            if (PDF_CONTENT_TYPE.equals(contentType)) {
                return compressPDF(fileData, fileName);
            }

            // Office documents and text files
            if (COMPRESSIBLE_TYPES.contains(contentType) || isTextBasedFile(contentType)) {
                return compressGeneral(fileData, contentType);
            }

            // Try general compression for unknown types
            return compressGeneral(fileData, contentType);

        } catch (Exception e) {
            log.error("Error compressing file {}: {}", fileName, e.getMessage(), e);
            return CompressionResult.noCompression("Compression failed: " + e.getMessage());
        }
    }

    /**
     * PDF-specific compression using PDFBox 3.0
     */
    private CompressionResult compressPDF(byte[] pdfData, String fileName) {
        PDDocument document = null;
        try {
            // Load PDF document using PDFBox 3.0 Loader API
            document = Loader.loadPDF(pdfData);

            // Get original info
            int originalPages = document.getNumberOfPages();
            long originalSize = pdfData.length;

            // Apply PDF compression techniques
            optimizePDFDocument(document);

            // Save compressed PDF (PDFBox 3.0 saves in compressed mode by default)
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            document.save(compressedOutput);

            byte[] compressedData = compressedOutput.toByteArray();

            if (isCompressionWorthwhile(originalSize, compressedData.length)) {
                double compressionRatio = (1.0 - (double) compressedData.length / originalSize) * 100;

                return CompressionResultImpl.builder()
                        .compressedData(compressedData)
                        .originalSize(originalSize)
                        .compressedSize(compressedData.length)
                        .compressionRatio(compressionRatio)
                        .method("PDF_OPTIMIZATION")
                        .compressed(true)
                        .metadata(Map.of(
                                "pages", String.valueOf(originalPages),
                            "technique", "PDFBox 3.0 optimization",
                            "defaultCompression", "enabled"
                        ))
                        .build();
            } else {
                return CompressionResultImpl.noCompression("PDF compression not beneficial");
            }

        } catch (IOException e) {
            log.error("Error compressing PDF {}: {}", fileName, e.getMessage());
            return compressGeneral(pdfData, PDF_CONTENT_TYPE); // Fallback to general compression
        } finally {
            // Ensure document is closed properly
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.warn("Error closing PDF document: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * General file compression using GZIP
     */
    private CompressionResult compressGeneral(byte[] fileData, String contentType) {
        try {
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();

            // Use GZIP with maximum compression
            GZIPOutputStream gzipOut = new GZIPOutputStream(compressedOutput) {
                {
                    // Set compression level to maximum
                    def.setLevel(Deflater.BEST_COMPRESSION);
                }
            };

            gzipOut.write(fileData);
            gzipOut.close();

            byte[] compressedData = compressedOutput.toByteArray();
            long originalSize = fileData.length;

            if (isCompressionWorthwhile(originalSize, compressedData.length)) {
                double compressionRatio = (1.0 - (double) compressedData.length / originalSize) * 100;

                return CompressionResultImpl.builder()
                        .compressedData(compressedData)
                        .originalSize(originalSize)
                        .compressedSize(compressedData.length)
                        .compressionRatio(compressionRatio)
                        .method("GZIP")
                        .compressed(true)
                        .metadata(Map.of(
                                "algorithm", "GZIP",
                                "level", "BEST_COMPRESSION"
                        ))
                        .build();
            } else {
                return CompressionResultImpl.noCompression("GZIP compression not beneficial");
            }

        } catch (IOException e) {
            log.error("Error applying GZIP compression: {}", e.getMessage());
            return CompressionResultImpl.noCompression("GZIP compression failed");
        }
    }

    /**
     * Decompress file based on compression method
     */
    @Override
    public byte[] decompressFile(byte[] compressedData, String compressionMethod) {
        if (compressedData == null || compressionMethod == null) {
            return compressedData;
        }

        try {
            return switch (compressionMethod.toUpperCase()) {
                case "GZIP" -> decompressGZIP(compressedData);
                case "PDF_OPTIMIZATION" -> compressedData; // PDF is already decompressed
                default -> {
                    log.warn("Unknown compression method: {}", compressionMethod);
                    yield compressedData;
                }
            };
        } catch (Exception e) {
            log.error("Error decompressing file with method {}: {}", compressionMethod, e.getMessage());
            return compressedData; // Return as-is if decompression fails
        }
    }

    /**
     * Get compression recommendations for a file type
     */
    @Override
    public CompressionRecommendation getCompressionRecommendation(String contentType, long fileSize) {
        if (fileSize > MAX_COMPRESSION_SIZE) {
            return CompressionRecommendationImpl.builder()
                    .recommended(false)
                    .reason("File too large")
                    .expectedSavings(0.0)
                    .method("NONE")
                    .build();
        }

        if (NON_COMPRESSIBLE_TYPES.contains(contentType)) {
            return CompressionRecommendationImpl.builder()
                    .recommended(false)
                    .reason("File type already compressed")
                    .expectedSavings(0.0)
                    .method("NONE")
                    .build();
        }

        String method = PDF_CONTENT_TYPE.equals(contentType) ? "PDF_OPTIMIZATION" : "GZIP";
        double expectedSavings = estimateCompressionSavings(contentType);

        return CompressionRecommendationImpl.builder()
                .recommended(expectedSavings > MIN_COMPRESSION_SAVINGS)
                .reason("Expected " + String.format("%.1f", expectedSavings * 100) + "% reduction")
                .expectedSavings(expectedSavings)
                .method(method)
                .build();
    }

    // Private helper methods

    private void optimizePDFDocument(PDDocument document) throws IOException {
        // Remove metadata to reduce size
        PDDocumentInformation info = document.getDocumentInformation();
        if (info != null) {
            // Keep essential info, remove verbose metadata
            info.setCreator(null);
            info.setProducer(null);
            info.setSubject(null);
            info.setKeywords(null);
        }

        // Remove XMP metadata
        document.getDocumentCatalog().setMetadata(null);

        // Additional optimizations can be added here:
        // - Image compression within PDF
        // - Font subsetting
        // - Content stream compression
    }

    private byte[] decompressGZIP(byte[] compressedData) throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(compressedData);
        java.util.zip.GZIPInputStream gzipIn = new java.util.zip.GZIPInputStream(inputStream);

        return gzipIn.readAllBytes();
    }

    private boolean isTextBasedFile(String contentType) {
        return contentType != null && (
                contentType.startsWith("text/") ||
                        contentType.contains("json") ||
                        contentType.contains("xml") ||
                        contentType.contains("csv")
        );
    }

    private boolean isCompressionWorthwhile(long originalSize, long compressedSize) {
        if (compressedSize >= originalSize) {
            return false;
        }

        double savings = 1.0 - (double) compressedSize / originalSize;
        return savings >= MIN_COMPRESSION_SAVINGS;
    }

    private double estimateCompressionSavings(String contentType) {
        return switch (contentType) {
            case "text/plain", "text/html", "text/css" -> 0.70; // 70% reduction
            case "application/json", "application/xml" -> 0.80; // 80% reduction
            case "application/pdf" -> 0.25; // 25% reduction
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> 0.60; // 60% reduction
            default -> isTextBasedFile(contentType) ? 0.50 : 0.20;
        };
    }

    // Implementation classes

    @lombok.Builder
    @lombok.Data
    public static class CompressionResultImpl implements CompressionResult {
        private final byte[] compressedData;
        private final long originalSize;
        private final long compressedSize;
        private final double compressionRatio;
        private final String method;
        private final boolean compressed;
        private final String reason;
        private final Map<String, String> metadata;

        public static CompressionResultImpl noCompression(String reason) {
            return CompressionResultImpl.builder()
                    .compressed(false)
                    .reason(reason)
                    .compressionRatio(0.0)
                    .method("NONE")
                    .build();
        }

        public long getSavedBytes() {
            return compressed ? originalSize - compressedSize : 0;
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class CompressionRecommendationImpl implements CompressionRecommendation {
        private final boolean recommended;
        private final String reason;
        private final double expectedSavings;
        private final String method;
    }
}