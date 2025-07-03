package com.bracit.fms.fms.service;

import com.bracit.fms.fms.config.FileStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageProcessingService {

    private final FileStorageProperties properties;

    public boolean isImageFile(String contentType) {
        if (contentType == null) return false;
        return contentType.startsWith("image/");
    }

    public boolean isSupportedImageFormat(String contentType) {
        if (!isImageFile(contentType)) return false;

        String format = contentType.substring(6).toLowerCase();
        Set<String> allowedFormats = properties.getImage().getAllowedFormats();
        return allowedFormats.contains(format);
    }

    public byte[] compressImage(byte[] imageData, String contentType) {
        if (!properties.getImage().getCompression().isEnabled()) {
            return imageData;
        }

        try {
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (originalImage == null) {
                log.warn("Could not read image data for compression");
                return imageData;
            }

            ByteArrayOutputStream compressedOutput = new ByteArrayOutputStream();
            String formatName = getFormatName(contentType);

            Thumbnails.of(originalImage)
                    .scale(1.0)
                    .outputQuality(properties.getImage().getCompression().getQuality())
                    .outputFormat(formatName)
                    .toOutputStream(compressedOutput);

            byte[] compressedData = compressedOutput.toByteArray();
            log.debug("Image compressed from {} bytes to {} bytes", imageData.length, compressedData.length);

            return compressedData;
        } catch (IOException e) {
            log.error("Error compressing image: {}", e.getMessage());
            return imageData;
        }
    }

    public byte[] generateThumbnail(byte[] imageData, String contentType) {
        if (!properties.getImage().getThumbnail().isEnabled()) {
            return null;
        }

        try {
            BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (originalImage == null) {
                log.warn("Could not read image data for thumbnail generation");
                return null;
            }

            ByteArrayOutputStream thumbnailOutput = new ByteArrayOutputStream();
            String formatName = getFormatName(contentType);

            Thumbnails.of(originalImage)
                    .size(properties.getImage().getThumbnail().getWidth(),
                            properties.getImage().getThumbnail().getHeight())
                    .outputFormat(formatName)
                    .toOutputStream(thumbnailOutput);

            byte[] thumbnailData = thumbnailOutput.toByteArray();
            log.debug("Thumbnail generated: {} bytes", thumbnailData.length);

            return thumbnailData;
        } catch (IOException e) {
            log.error("Error generating thumbnail: {}", e.getMessage());
            return null;
        }
    }

    private String getFormatName(String contentType) {
        String format = contentType.substring(6).toLowerCase();
        return switch (format) {
            case "jpeg" -> "jpg";
            default -> format;
        };
    }
}