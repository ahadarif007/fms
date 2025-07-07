package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessingService {

    private final FileStorageProperties properties;

    // Supported image formats for validation
    private static final Set<String> SUPPORTED_FORMATS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    // Lossy formats that benefit from quality compression
    private static final Set<String> LOSSY_FORMATS = Set.of("jpg", "jpeg", "webp");

    // Maximum image dimensions for security (prevent memory exhaustion)
    private static final int MAX_IMAGE_WIDTH = 10000;
    private static final int MAX_IMAGE_HEIGHT = 10000;

    // Minimum compression savings threshold (10%)
    private static final double MIN_COMPRESSION_SAVINGS = 0.1;

    /**
     * Check if content type represents an image file
     */
    public boolean isImageFile(String contentType) {
        return StringUtils.hasText(contentType) && contentType.toLowerCase().startsWith("image/");
    }

    /**
     * Check if image format is supported for processing
     */
    public boolean isSupportedImageFormat(String contentType) {
        if (!isImageFile(contentType)) {
            return false;
        }

        String format = extractFormatFromContentType(contentType);
        Set<String> allowedFormats = properties.getImage().getAllowedFormats();

        // Check both configured formats and inherently supported formats
        return allowedFormats.contains(format) && SUPPORTED_FORMATS.contains(format);
    }

    /**
     * Compress image with intelligent format-specific optimization
     */
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
            if (isCompressionWorthwhile(imageData.length, compressedData.length)) {
                log.debug("Image compressed from {} bytes to {} bytes ({:.1f}% reduction)",
                        imageData.length, compressedData.length,
                        (1.0 - (double)compressedData.length / imageData.length) * 100);
                return compressedData;
            } else {
                log.debug("Compression not worthwhile, returning original image");
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

    /**
     * Generate thumbnail with aspect ratio preservation and smart cropping
     */
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

    /**
     * Generate multiple thumbnail sizes
     */
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
            return java.util.Arrays.stream(sizes)
                    .mapToObj(size -> generateSingleThumbnail(originalImage, formatName, size))
                    .filter(data -> data != null)
                    .toList();

        } catch (Exception e) {
            log.error("Error generating multiple thumbnails: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get image metadata without full processing
     */
    public ImageMetadata getImageMetadata(byte[] imageData) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));
            if (image == null) {
                return null;
            }

            return ImageMetadata.builder()
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

    // Private helper methods

    private BufferedImage readAndValidateImage(byte[] imageData) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));

        if (image == null) {
            log.warn("Could not read image data - unsupported format or corrupted data");
            return null;
        }

        // Security check: prevent memory exhaustion attacks
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
            case "svg+xml" -> "png"; // Convert SVG to PNG
            default -> format;
        };
    }

    private float getOptimalQuality(String contentType) {
        String format = extractFormatFromContentType(contentType);

        // Use different quality settings based on format
        return switch (format) {
            case "jpeg", "jpg" -> properties.getImage().getCompression().getQuality();
            case "webp" -> Math.min(properties.getImage().getCompression().getQuality() + 0.1f, 1.0f);
            default -> 1.0f; // No quality compression for lossless formats
        };
    }

    private boolean isCompressionWorthwhile(int originalSize, int compressedSize) {
        if (compressedSize >= originalSize) {
            return false; // No savings or larger
        }

        double savings = 1.0 - (double) compressedSize / originalSize;
        return savings >= MIN_COMPRESSION_SAVINGS;
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

    // Inner class for image metadata
    @lombok.Builder
    @lombok.Data
    public static class ImageMetadata {
        private final int width;
        private final int height;
        private final long sizeInBytes;
        private final boolean hasAlpha;

        public double getAspectRatio() {
            return height == 0 ? 0 : (double) width / height;
        }

        public long getPixelCount() {
            return (long) width * height;
        }
    }
}