package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingServiceImpl implements FileProcessingService {

    private final FileStorageProperties properties;

    // ========== COMPRESSION CONSTANTS ==========

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

    // ========== IMAGE PROCESSING CONSTANTS ==========

    // Supported image formats for validation
    private static final Set<String> SUPPORTED_IMAGE_FORMATS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    // Lossy formats that benefit from quality compression
    private static final Set<String> LOSSY_FORMATS = Set.of("jpg", "jpeg", "webp");

    // Maximum image dimensions for security (prevent memory exhaustion)
    private static final int MAX_IMAGE_WIDTH = 10000;
    private static final int MAX_IMAGE_HEIGHT = 10000;

    // Minimum image compression savings threshold (10%)
    private static final double MIN_IMAGE_COMPRESSION_SAVINGS = 0.1;

    // ========== COMPRESSION OPERATIONS ==========

    @Override
    public CompressionResult compressFile(byte[] fileData, String contentType, String fileName) {
        if (fileData == null || fileData.length == 0) {
            return CompressionResultImpl.noCompression("Empty file");
        }

        if (fileData.length > MAX_COMPRESSION_SIZE) {
            return CompressionResultImpl.noCompression("File too large for compression");
        }

        try {
            // Check if file type is worth compressing
            if (NON_COMPRESSIBLE_TYPES.contains(contentType)) {
                return CompressionResultImpl.noCompression("File type already compressed");
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
            return CompressionResultImpl.noCompression("Compression failed: " + e.getMessage());
        }
    }

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

    // ========== IMAGE PROCESSING OPERATIONS ==========

    @Override
    public boolean isImageFile(String contentType) {
        return StringUtils.hasText(contentType) && contentType.toLowerCase().startsWith("image/");
    }

    @Override
    public boolean isSupportedImageFormat(String contentType) {
        if (!isImageFile(contentType)) {
            return false;
        }

        String format = extractFormatFromContentType(contentType);
        Set<String> allowedFormats = properties.getImage().getAllowedFormats();

        // Check both configured formats and inherently supported formats
        return allowedFormats.contains(format) && SUPPORTED_IMAGE_FORMATS.contains(format);
    }

    @Override
    public byte[] compressImage(byte[] imageData, String contentType) {
        if (!properties.getImage().getCompression().isEnabled() || imageData == null || imageData.length == 0) {
            return imageData;
        }

        try {
            BufferedImage originalImage = readAndValidateImage(imageData);
            if (originalImage == null) {
                return imageData;
            }

            String formatName = getOptimalOutputFormat(contentType);
            float quality = getOptimalQuality(contentType);

            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();

            Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(originalImage)
                    .scale(1.0)
                    .outputFormat(formatName);

            // Apply quality only for lossy formats
            if (LOSSY_FORMATS.contains(formatName.toLowerCase())) {
                builder.outputQuality(quality);
            }

            builder.toOutputStream(compressedOutput);

            byte[] compressedData = compressedOutput.toByteArray();

            // Only return compressed version if there's meaningful savings
            if (isImageCompressionWorthwhile(imageData.length, compressedData.length)) {
                log.debug("Image compressed from {} bytes to {} bytes ({:.1f}% reduction)",
                        imageData.length, compressedData.length,
                        (1.0 - (double)compressedData.length / imageData.length) * 100);
                return compressedData;
            } else {
                log.debug("Image compression not worthwhile, returning original image");
                return imageData;
            }

        } catch (IOException e) {
            log.error("Error compressing image: {}", e.getMessage(), e);
            return imageData;
        } catch (Exception e) {
            log.error("Unexpected error during image compression: {}", e.getMessage(), e);
            return imageData;
        }
    }

    @Override
    public byte[] generateThumbnail(byte[] imageData, String contentType) {
        if (!properties.getImage().getThumbnail().isEnabled() || imageData == null || imageData.length == 0) {
            return null;
        }

        try {
            BufferedImage originalImage = readAndValidateImage(imageData);
            if (originalImage == null) {
                return null;
            }

            String formatName = getOptimalOutputFormat(contentType);
            int targetWidth = properties.getImage().getThumbnail().getWidth();
            int targetHeight = properties.getImage().getThumbnail().getHeight();

            ByteArrayOutputStream thumbnailOutput = new ByteArrayOutputStream();

            Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(originalImage)
                    .size(targetWidth, targetHeight)
                    .outputFormat(formatName)
                    .keepAspectRatio(true); // Maintain aspect ratio

            // Apply quality for lossy formats
            if (LOSSY_FORMATS.contains(formatName.toLowerCase())) {
                builder.outputQuality(0.85f); // Slightly higher quality for thumbnails
            }

            builder.toOutputStream(thumbnailOutput);

            byte[] thumbnailData = thumbnailOutput.toByteArray();
            log.debug("Thumbnail generated: {} bytes ({}x{} -> {}x{})",
                    thumbnailData.length, originalImage.getWidth(), originalImage.getHeight(),
                    targetWidth, targetHeight);

            return thumbnailData;

        } catch (IOException e) {
            log.error("Error generating thumbnail: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error during thumbnail generation: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public List<byte[]> generateMultipleThumbnails(byte[] imageData, String contentType, int... sizes) {
        if (!properties.getImage().getThumbnail().isEnabled() || imageData == null || sizes.length == 0) {
            return List.of();
        }

        try {
            BufferedImage originalImage = readAndValidateImage(imageData);
            if (originalImage == null) {
                return List.of();
            }

            String formatName = getOptimalOutputFormat(contentType);
            return Arrays.stream(sizes)
                    .mapToObj(size -> generateSingleThumbnail(originalImage, formatName, size))
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception e) {
            log.error("Error generating multiple thumbnails: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public ImageMetadata getImageMetadata(byte[] imageData) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return null;
            }

            return ImageMetadataImpl.builder()
                    .width(image.getWidth())
                    .height(image.getHeight())
                    .sizeInBytes(imageData.length)
                    .hasAlpha(image.getColorModel().hasAlpha())
                    .build();

        } catch (IOException e) {
            log.error("Error reading image metadata: {}", e.getMessage());
            return null;
        }
    }

    // ========== COMBINED OPERATIONS ==========

    @Override
    public ProcessingResult processFile(byte[] fileData, String contentType, String fileName) {
        return processFile(fileData, contentType, fileName, ProcessingOptions.defaultOptions());
    }

    @Override
    public ProcessingResult processFile(byte[] fileData, String contentType, String fileName, ProcessingOptions options) {
        if (fileData == null || fileData.length == 0) {
            return ProcessingResultImpl.noProcessing("Empty file");
        }

        byte[] processedData = fileData;
        byte[] thumbnailData = null;
        CompressionResult compressionResult = null;
        ImageMetadata imageMetadata = null;
        String processingMethod = "NONE";
        Map<String, Object> processingMetadata = new HashMap<>();
        long totalSavedBytes = 0;

        try {
            // Handle image files
            if (isImageFile(contentType)) {
                imageMetadata = getImageMetadata(fileData);

                if (options.isImageOptimizationEnabled()) {
                    processedData = compressImage(fileData, contentType);
                    if (!Arrays.equals(processedData, fileData)) {
                        processingMethod = "IMAGE_OPTIMIZATION";
                        totalSavedBytes += (fileData.length - processedData.length);
                        processingMetadata.put("imageOptimized", true);
                    }
                }

                if (options.isThumbnailGenerationEnabled()) {
                    thumbnailData = generateThumbnail(fileData, contentType);
                    if (thumbnailData != null) {
                        processingMetadata.put("thumbnailGenerated", true);
                        processingMetadata.put("thumbnailSize", thumbnailData.length);
                    }
                }
            }

            // Handle compression for non-image files or additional compression
            if (options.isCompressionEnabled() && !isImageFile(contentType)) {
                compressionResult = compressFile(processedData, contentType, fileName);

                if (compressionResult.isCompressed()) {
                    processedData = compressionResult.getCompressedData();
                    totalSavedBytes += compressionResult.getSavedBytes();
                    processingMethod = processingMethod.equals("NONE") ?
                            compressionResult.getMethod() :
                            processingMethod + "_" + compressionResult.getMethod();
                    processingMetadata.put("compressionApplied", true);
                    processingMetadata.put("compressionMethod", compressionResult.getMethod());
                }
            }

            processingMetadata.put("originalSize", fileData.length);
            processingMetadata.put("processedSize", processedData.length);
            processingMetadata.put("totalSavedBytes", totalSavedBytes);

            return ProcessingResultImpl.builder()
                    .processedData(processedData)
                    .thumbnailData(thumbnailData)
                    .compressionResult(compressionResult)
                    .imageMetadata(imageMetadata)
                    .processed(!Arrays.equals(processedData, fileData) || thumbnailData != null)
                    .processingMethod(processingMethod)
                    .processingMetadata(processingMetadata)
                    .totalSavedBytes(totalSavedBytes)
                    .build();

        } catch (Exception e) {
            log.error("Error processing file {}: {}", fileName, e.getMessage(), e);
            return ProcessingResultImpl.noProcessing("Processing failed: " + e.getMessage());
        }
    }

    // ========== PRIVATE HELPER METHODS ==========

    // Compression helpers
    private CompressionResult compressPDF(byte[] pdfData, String fileName) {
        PDDocument document = null;
        try {
            document = Loader.loadPDF(pdfData);
            int originalPages = document.getNumberOfPages();
            long originalSize = pdfData.length;

            optimizePDFDocument(document);

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
            return compressGeneral(pdfData, PDF_CONTENT_TYPE);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.warn("Error closing PDF document: {}", e.getMessage());
                }
            }
        }
    }

    private CompressionResult compressGeneral(byte[] fileData, String contentType) {
        try {
            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();

            GZIPOutputStream gzipOut = new GZIPOutputStream(compressedOutput) {
                {
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

    private void optimizePDFDocument(PDDocument document) throws IOException {
        PDDocumentInformation info = document.getDocumentInformation();
        if (info != null) {
            info.setCreator(null);
            info.setProducer(null);
            info.setSubject(null);
            info.setKeywords(null);
        }
        document.getDocumentCatalog().setMetadata(null);
    }

    private byte[] decompressGZIP(byte[] compressedData) throws IOException {
        ByteArrayInputStream inputStream = new ByteArrayInputStream(compressedData);
        GZIPInputStream gzipIn = new GZIPInputStream(inputStream);
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
            case "text/plain", "text/html", "text/css" -> 0.70;
            case "application/json", "application/xml" -> 0.80;
            case "application/pdf" -> 0.25;
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> 0.60;
            default -> isTextBasedFile(contentType) ? 0.50 : 0.20;
        };
    }

    // Image processing helpers
    private BufferedImage readAndValidateImage(byte[] imageData) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));

        if (image == null) {
            log.warn("Could not read image data - unsupported format or corrupted data");
            return null;
        }

        if (image.getWidth() > MAX_IMAGE_WIDTH || image.getHeight() > MAX_IMAGE_HEIGHT) {
            log.warn("Image dimensions too large: {}x{} (max: {}x{})",
                    image.getWidth(), image.getHeight(), MAX_IMAGE_WIDTH, MAX_IMAGE_HEIGHT);
            return null;
        }

        return image;
    }

    private String extractFormatFromContentType(String contentType) {
        if (!StringUtils.hasText(contentType) || !contentType.contains("/")) {
            return "";
        }
        return contentType.substring(contentType.indexOf("/") + 1).toLowerCase();
    }

    private String getOptimalOutputFormat(String contentType) {
        String format = extractFormatFromContentType(contentType);
        return switch (format) {
            case "jpeg" -> "jpg";
            case "svg+xml" -> "png";
            default -> format;
        };
    }

    private float getOptimalQuality(String contentType) {
        String format = extractFormatFromContentType(contentType);
        return switch (format) {
            case "jpeg", "jpg" -> properties.getImage().getCompression().getQuality();
            case "webp" -> Math.min(properties.getImage().getCompression().getQuality() + 0.1f, 1.0f);
            default -> 1.0f;
        };
    }

    private boolean isImageCompressionWorthwhile(int originalSize, int compressedSize) {
        if (compressedSize >= originalSize) {
            return false;
        }
        double savings = 1.0 - (double) compressedSize / originalSize;
        return savings >= MIN_IMAGE_COMPRESSION_SAVINGS;
    }

    private byte[] generateSingleThumbnail(BufferedImage originalImage, String formatName, int size) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(originalImage)
                    .size(size, size)
                    .outputFormat(formatName)
                    .keepAspectRatio(true);

            if (LOSSY_FORMATS.contains(formatName.toLowerCase())) {
                builder.outputQuality(0.85f);
            }

            builder.toOutputStream(output);
            return output.toByteArray();

        } catch (IOException e) {
            log.error("Error generating thumbnail of size {}: {}", size, e.getMessage());
            return null;
        }
    }

    // ========== IMPLEMENTATION CLASSES ==========

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

    @lombok.Builder
    @lombok.Data
    public static class ImageMetadataImpl implements ImageMetadata {
        private final int width;
        private final int height;
        private final long sizeInBytes;
        private final boolean hasAlpha;

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public long getSizeInBytes() {
            return sizeInBytes;
        }

        @Override
        public boolean hasAlpha() {
            return hasAlpha;
        }

        @Override
        public double getAspectRatio() {
            return height == 0 ? 0 : (double) width / height;
        }

        @Override
        public long getPixelCount() {
            return (long) width * height;
        }
    }

    @lombok.Builder
    @lombok.Data
    public static class ProcessingResultImpl implements ProcessingResult {
        private final byte[] processedData;
        private final byte[] thumbnailData;
        private final CompressionResult compressionResult;
        private final ImageMetadata imageMetadata;
        private final boolean processed;
        private final String processingMethod;
        private final Map<String, Object> processingMetadata;
        private final long totalSavedBytes;

        public static ProcessingResultImpl noProcessing(String reason) {
            return ProcessingResultImpl.builder()
                    .processed(false)
                    .processingMethod("NONE")
                    .processingMetadata(Map.of("reason", reason))
                    .totalSavedBytes(0)
                    .build();
        }

        public boolean wasProcessed() {
            return processed;
        }
    }
}