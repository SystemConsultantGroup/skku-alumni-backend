package com.scg.alumni.api.operations;

import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.image.ImageCompressor;
import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/profile-images")
public class ProfileImageController {

    /** 수신 한도. 이 크기까지 받아서 {@link ImageCompressor}가 줄인다. */
    private static final long MAX_UPLOAD_SIZE = 30L * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_GIF_VALUE,
            "image/webp");

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ImageCompressor imageCompressor;

    @PostMapping
    public Map<String, String> upload(@RequestPart("file") MultipartFile file) {
        validate(file);
        ImageCompressor.Result image;
        try {
            image = imageCompressor.compress(file, file.getContentType().toLowerCase(Locale.ROOT));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("프로필 사진을 변환하지 못했습니다.", exception);
        }
        String fileName = UUID.randomUUID() + extension(image.contentType());
        String objectName = "profiles/" + fileName;

        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectName)
                    .contentType(image.contentType())
                    .stream(new java.io.ByteArrayInputStream(image.bytes()), image.size(), -1L)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("프로필 사진 저장에 실패했습니다.", exception);
        }

        String imageUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/profile-images/")
                .path(fileName)
                .build()
                .toUriString();
        jdbcTemplate.update("""
                update users
                set profile_image_url = ?, updated_at = CURRENT_TIMESTAMP
                where id = ?
                """, imageUrl, AuthContext.currentMemberId());
        return Map.of("profileImageUrl", imageUrl);
    }

    @GetMapping("/{fileName:[a-f0-9\\-]+\\.[a-z]+}")
    public ResponseEntity<byte[]> find(@PathVariable String fileName) {
        String objectName = "profiles/" + fileName;
        try {
            StatObjectResponse metadata = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectName)
                    .build());
            byte[] content;
            try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectName)
                    .build())) {
                content = input.readAllBytes();
            }
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .contentType(MediaType.parseMediaType(metadata.contentType()))
                    .contentLength(content.length)
                    .body(content);
        } catch (io.minio.errors.ErrorResponseException exception) {
            throw new ResponseStatusException(NOT_FOUND, "프로필 사진을 찾을 수 없습니다.");
        } catch (Exception exception) {
            throw new IllegalStateException("프로필 사진을 불러오지 못했습니다.", exception);
        }
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "이미지 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_UPLOAD_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "이미지는 30MB 이하만 업로드할 수 있습니다.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(BAD_REQUEST, "JPG, PNG, GIF, WEBP 이미지만 업로드할 수 있습니다.");
        }
        if (!hasMatchingSignature(file, contentType.toLowerCase(Locale.ROOT))) {
            throw new ResponseStatusException(BAD_REQUEST, "파일 내용이 이미지 형식과 일치하지 않습니다.");
        }
    }

    private boolean hasMatchingSignature(MultipartFile file, String contentType) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(12);
            return switch (contentType) {
                case MediaType.IMAGE_JPEG_VALUE -> header.length >= 3
                        && unsigned(header[0]) == 0xff && unsigned(header[1]) == 0xd8 && unsigned(header[2]) == 0xff;
                case MediaType.IMAGE_PNG_VALUE -> header.length >= 8
                        && unsigned(header[0]) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                        && unsigned(header[4]) == 0x0d && unsigned(header[5]) == 0x0a
                        && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a;
                case MediaType.IMAGE_GIF_VALUE -> header.length >= 6
                        && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                        && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
                case "image/webp" -> header.length >= 12
                        && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
                default -> false;
            };
        } catch (IOException exception) {
            return false;
        }
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(minioProperties.bucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(minioProperties.bucket())
                    .build());
        }
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case MediaType.IMAGE_GIF_VALUE -> ".gif";
            case "image/webp" -> ".webp";
            default -> throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다.");
        };
    }
}
