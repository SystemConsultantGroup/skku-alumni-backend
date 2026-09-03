package com.scg.alumni.infrastructure.image;

import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 프로필 사진을 검사하고 저장한다.
 *
 * <p>본인이 올리는 경로와 사무처가 회원 상세 화면에서 대신 올리는 경로가 같은
 * 검사·변환·저장을 거쳐야 한다. 두 곳에 같은 코드를 두면 한쪽만 고치기 쉽다.
 */
@Component
@RequiredArgsConstructor
public class ProfileImageStorage {

    /** 수신 한도. 이 크기까지 받아서 {@link ImageCompressor}가 줄인다. */
    public static final long MAX_UPLOAD_SIZE = 30L * 1024 * 1024;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_GIF_VALUE,
            "image/webp");

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ImageCompressor imageCompressor;

    /** 검사·변환·저장을 마치고 내려받을 수 있는 주소를 준다. */
    public String store(MultipartFile file) {
        validate(file);
        ImageCompressor.Result image;
        try {
            image = imageCompressor.compress(file, file.getContentType().toLowerCase(Locale.ROOT));
        } catch (IOException exception) {
            throw new IllegalStateException("프로필 사진을 변환하지 못했습니다.", exception);
        }
        String fileName = UUID.randomUUID() + extension(image.contentType());
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object("profiles/" + fileName)
                    .contentType(image.contentType())
                    .stream(new ByteArrayInputStream(image.bytes()), image.size(), -1L)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("프로필 사진 저장에 실패했습니다.", exception);
        }
        // 절대 주소가 아니라 경로를 돌려준다. 절대 주소를 저장하면 사진이
        // 올릴 때 쓴 호스트에 묶여, 도메인이 바뀌면 통째로 깨진다.
        return "/api/v1/profile-images/" + fileName;
    }

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
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

    /**
     * 확장자와 Content-Type 은 보내는 쪽이 정한다. 파일 앞머리를 직접 읽어
     * 실제로 그 형식인지 확인한다.
     */
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
