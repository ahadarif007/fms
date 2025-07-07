# File Storage Service

A comprehensive Spring Boot application that provides file storage capabilities across multiple cloud providers and local filesystem, with built-in image compression and thumbnail generation.

## Features

- **Multi-Provider Support**: Local filesystem, AWS S3, MinIO, Google Cloud Storage, Azure Blob Storage
- **Image Processing**: Automatic image compression and thumbnail generation
- **RESTful API**: Complete CRUD operations for file management
- **Easy Configuration**: Switch providers by changing properties file
- **Error Handling**: Comprehensive exception handling and logging
- **Health Checks**: Built-in health monitoring endpoints

## Supported Providers

1. **Local File System** - Store files on local disk
2. **AWS S3** - Amazon Simple Storage Service
3. **MinIO** - Self-hosted S3-compatible storage
4. **Google Cloud Storage** - Google's cloud storage service
5. **Azure Blob Storage** - Microsoft's cloud storage service

## Quick Start

### 1. Clone and Build

```bash
git clone <repository-url>
cd file-storage-service
./gradlew build
```

### 2. Configure Provider

Edit `application.properties` to set your desired provider:

```properties
# Change this to: local, s3, minio, gcs, or azure
file.storage.provider=local
```

### 3. Provider-Specific Configuration

#### Local File System
```properties
file.storage.local.base-path=./uploads
```

#### AWS S3
```properties
file.storage.s3.bucket-name=your-s3-bucket
file.storage.s3.region=us-east-1
file.storage.s3.access-key=your-access-key
file.storage.s3.secret-key=your-secret-key
```

#### MinIO
```properties
file.storage.minio.endpoint=http://localhost:9000
file.storage.minio.access-key=minioadmin
file.storage.minio.secret-key=minioadmin
file.storage.minio.bucket-name=your-minio-bucket
```

#### Google Cloud Storage
```properties
file.storage.gcs.project-id=your-project-id
file.storage.gcs.bucket-name=your-gcs-bucket
file.storage.gcs.credentials-path=path/to/service-account.json
```

#### Azure Blob Storage
```properties
file.storage.azure.connection-string=DefaultEndpointsProtocol=https;AccountName=your-account;AccountKey=your-key;EndpointSuffix=core.windows.net
file.storage.azure.container-name=your-container
```

### 4. Run the Application

```bash
./gradlew bootRun
```

The application will start on `http://localhost:8080`

## API Endpoints

### Upload File
```http
POST /api/files/upload
Content-Type: multipart/form-data

Parameters:
- file: The file to upload
- compressImage: (optional, default: true) Compress images
- generateThumbnail: (optional, default: true) Generate thumbnails for images
```

### Download File
```http
GET /api/files/{fileId}/download
```

### Get File Information
```http
GET /api/files/{fileId}/info
```

### Update File Information
```http
PUT /api/files/{fileId}
Content-Type: application/json

{
  "originalFileName": "new-name.jpg"
}
```

### Delete File
```http
DELETE /api/files/{fileId}
```

### Get Thumbnail
```http
GET /api/files/{fileId}/thumbnail
```

### List All Files
```http
GET /api/files/list
```

### Check File Exists
```http
GET /api/files/{fileId}/exists
```

### Get Current Provider
```http
GET /api/files/provider
```

## Image Processing Configuration

Configure image compression and thumbnail generation:

```properties
# Image Processing
file.storage.image.compression.enabled=true
file.storage.image.compression.quality=0.8
file.storage.image.thumbnail.enabled=true
file.storage.image.thumbnail.width=200
file.storage.image.thumbnail.height=200
file.storage.image.allowed-formats=jpg,jpeg,png,gif,bmp,webp
```

## Example Usage

### Upload a File
```bash
curl -X POST \
  http://localhost:8080/api/files/upload \
  -F 'file=@/path/to/your/image.jpg' \
  -F 'compressImage=true' \
  -F 'generateThumbnail=true'
```

### Download a File
```bash
curl -X GET \
  http://localhost:8080/api/files/{fileId}/download \
  -o downloaded-file.jpg
```

### Get Thumbnail
```bash
curl -X GET \
  http://localhost:8080/api/files/{fileId}/thumbnail \
  -o thumbnail.jpg
```

## Architecture

The application follows a clean architecture pattern with:

- **Single Interface**: `FileStorageService` interface for all providers
- **Provider Implementations**: Separate service classes for each storage provider
- **Conditional Beans**: Spring's `@ConditionalOnProperty` for provider selection
- **Image Processing**: Dedicated service for image compression and thumbnail generation
- **Configuration**: Centralized properties management

## Technologies Used

- **Spring Boot 3.2.0**
- **Java 17**
- **Gradle**
- **AWS SDK v2**
- **MinIO Java Client**
- **Google Cloud Storage**
- **Azure Storage SDK**
- **Thumbnailator** (for image processing)
- **Lombok**

## Health Monitoring

Access health endpoints:
- Health: `GET /actuator/health`
- Info: `GET /actuator/info`

## Error Handling

The application includes comprehensive error handling:
- File size limit exceeded
- Invalid file formats
- Provider-specific errors
- General runtime exceptions

## Development

### Adding a New Provider

1. Create a new service class implementing `FileStorageService`
2. Add `@ConditionalOnProperty` annotation
3. Add configuration properties to `FileStorageProperties`
4. Update `application.properties` with new provider settings

### Running Tests

```bash
./gradlew test
```

## Production Considerations
1**Caching**: Implement caching for frequently accessed files
<< not needed now >>
2**Backup**: Configure cross-provider backup strategies
<< not needed now >>

## License

This project is licensed under the MIT License.