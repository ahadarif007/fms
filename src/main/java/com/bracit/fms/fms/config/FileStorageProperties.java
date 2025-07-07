package com.bracit.fms.fms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "file.storage")
public class FileStorageProperties {

    private String provider = "local";

    private Local local = new Local();
    private S3 s3 = new S3();
    private Minio minio = new Minio();
    private Gcs gcs = new Gcs();
    private Azure azure = new Azure();
    private Image image = new Image();
    private Compression compression = new Compression();

    @Data
    public static class Local {
        private String basePath = "./uploads";
    }

    @Data
    public static class Compression {
        private boolean enabled = true;
        private double minSavingsThreshold = 0.15; // 15%
        private long maxFileSizeBytes = 100 * 1024 * 1024; // 100MB
        private boolean compressPdf = true;
        private boolean compressDocuments = true;
        private boolean compressText = true;
    }
    @Data
    public static class S3 {
        private String bucketName;
        private String region;
        private String accessKey;
        private String secretKey;
    }

    @Data
    public static class Minio {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucketName;
    }

    @Data
    public static class Gcs {
        private String projectId;
        private String bucketName;
        private String credentialsPath;
    }

    @Data
    public static class Azure {
        private String connectionString;
        private String containerName;
    }

    @Data
    public static class Image {
        private Compression compression = new Compression();
        private Thumbnail thumbnail = new Thumbnail();
        private Set<String> allowedFormats = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

        @Data
        public static class Compression {
            private boolean enabled = true;
            private float quality = 0.8f;
        }

        @Data
        public static class Thumbnail {
            private boolean enabled = true;
            private int width = 200;
            private int height = 200;
        }
    }
}