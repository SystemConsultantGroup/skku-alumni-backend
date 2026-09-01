package com.scg.alumni.api.operations;

import com.scg.alumni.global.security.AuthContext;
import com.scg.alumni.infrastructure.image.ProfileImageStorage;
import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.InputStream;
import java.util.Map;
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

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/profile-images")
public class ProfileImageController {

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ProfileImageStorage profileImageStorage;

    @PostMapping
    public Map<String, String> upload(@RequestPart("file") MultipartFile file) {
        String imageUrl = profileImageStorage.store(file);
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
}
