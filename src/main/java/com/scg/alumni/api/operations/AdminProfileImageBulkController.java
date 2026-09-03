package com.scg.alumni.api.operations;

import com.scg.alumni.infrastructure.image.ImageCompressor;
import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * 사무처가 갖고 있던 임원 사진을 한 번에 올린다.
 *
 * <p>사무처는 임원이 이사로 위촉될 때마다 사진을 받아 파일로 모아 두었다.
 * 서비스를 열 때 이 사진들을 미리 넣어 두기로 회의에서 정했다. 회원 각자가
 * 다시 올릴 때까지 프로필이 비어 있으면 명단이 허전하기 때문이다.
 *
 * <p>사진 폴더를 통째로 압축해 올리면 파일명으로 회원을 찾아 붙인다.
 * 파일명은 학번이 가장 확실하고, 이름도 받아준다. 다만 동명이인이 있으면
 * 잘못 붙는 것보다 건너뛰는 편이 낫다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/members/profile-images")
public class AdminProfileImageBulkController {

    private static final long MAX_ARCHIVE_SIZE = 200L * 1024 * 1024;
    private static final long MAX_ENTRY_SIZE = 30L * 1024 * 1024;
    private static final int MAX_ENTRIES = 3_000;

    private final JdbcTemplate jdbcTemplate;
    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final ImageCompressor imageCompressor;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw badRequest("사진 압축 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_ARCHIVE_SIZE) {
            throw badRequest("압축 파일은 200MB 이하만 올릴 수 있습니다.");
        }
        // ZipInputStream 은 zip 이 아닌 스트림에서도 예외 없이 "항목 0개" 로 끝난다.
        // 그대로 두면 엉뚱한 파일을 올린 사무처에게 "사진 0장을 등록했습니다" 라는
        // 성공 문구가 나가고, 무엇을 고쳐야 하는지 알 길이 없다.
        requireZipSignature(file);

        int matched = 0;
        List<String> unmatched = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int entries = 0;

        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            ensureBucket();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX")) {
                    continue;
                }
                if (++entries > MAX_ENTRIES) {
                    throw badRequest("한 번에 최대 " + MAX_ENTRIES + "장까지 올릴 수 있습니다.");
                }
                String fileName = baseName(entry.getName());
                String contentType = contentTypeOf(entry.getName());
                if (contentType == null) {
                    skipped.add(entry.getName());
                    continue;
                }
                byte[] content = zip.readNBytes((int) MAX_ENTRY_SIZE + 1);
                if (content.length > MAX_ENTRY_SIZE) {
                    skipped.add(entry.getName());
                    continue;
                }

                List<Long> candidates = findMemberIds(fileName);
                if (candidates.isEmpty()) {
                    unmatched.add(entry.getName());
                    continue;
                }
                if (candidates.size() > 1) {
                    ambiguous.add(entry.getName());
                    continue;
                }
                store(candidates.get(0), content, contentType);
                matched++;
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw badRequest("압축 파일을 읽을 수 없습니다. .zip 형식인지 확인해주세요.");
        }

        if (entries == 0) {
            throw badRequest("압축 파일 안에 사진이 없습니다. 사진 파일을 담아 다시 압축해주세요.");
        }

        return Map.of(
                "matched", matched,
                "unmatched", unmatched,
                "ambiguous", ambiguous,
                "skipped", skipped);
    }

    /** 앞 네 바이트로 zip 인지 본다. PK\x03\x04 는 일반, PK\x05\x06 은 빈 압축 파일이다. */
    private void requireZipSignature(MultipartFile file) {
        byte[] head = new byte[4];
        try (java.io.InputStream input = file.getInputStream()) {
            if (input.readNBytes(head, 0, 4) < 4) {
                throw badRequest("압축(zip) 파일이 아닙니다. 사진 폴더를 zip 으로 압축해 올려주세요.");
            }
        } catch (IOException exception) {
            throw badRequest("압축 파일을 읽을 수 없습니다. .zip 형식인지 확인해주세요.");
        }
        boolean zip = head[0] == 'P' && head[1] == 'K'
                && (head[2] == 3 || head[2] == 5 || head[2] == 7)
                && (head[3] == 4 || head[3] == 6 || head[3] == 8);
        if (!zip) {
            throw badRequest("압축(zip) 파일이 아닙니다. 사진 폴더를 zip 으로 압축해 올려주세요.");
        }
    }

    /** 학번이 먼저다. 학번으로 못 찾으면 이름으로 찾되, 동명이인은 붙이지 않는다. */
    private List<Long> findMemberIds(String key) {
        List<Long> byStudentId = jdbcTemplate.query(
                "select id from users where student_id = ? and deleted_at is null and status <> 'WITHDRAWN'",
                (resultSet, rowNum) -> resultSet.getLong(1), key);
        if (!byStudentId.isEmpty()) {
            return byStudentId;
        }
        return jdbcTemplate.query(
                "select id from users where replace(name, ' ', '') = replace(?, ' ', '') and deleted_at is null and status <> 'WITHDRAWN'",
                (resultSet, rowNum) -> resultSet.getLong(1), key);
    }

    private void store(Long memberId, byte[] content, String contentType) {
        ImageCompressor.Result image;
        try {
            image = imageCompressor.compress(content, contentType);
        } catch (IOException exception) {
            throw new IllegalStateException("사진을 변환하지 못했습니다.", exception);
        }
        String fileName = UUID.randomUUID() + extension(image.contentType());
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object("profiles/" + fileName)
                    .contentType(image.contentType())
                    .stream(new ByteArrayInputStream(image.bytes()), image.size(), -1L)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("사진 저장에 실패했습니다.", exception);
        }
        String imageUrl = "/api/v1/profile-images/" + fileName;
        jdbcTemplate.update("""
                update users set profile_image_url = ?, updated_at = CURRENT_TIMESTAMP where id = ?
                """, imageUrl, memberId);
    }

    private String baseName(String entryName) {
        String name = entryName.substring(entryName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).trim();
    }

    private String contentTypeOf(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG_VALUE;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG_VALUE;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF_VALUE;
        if (lower.endsWith(".webp")) return "image/webp";
        return null;
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case MediaType.IMAGE_GIF_VALUE -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private void ensureBucket() throws IOException {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.bucket()).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.bucket()).build());
            }
        } catch (Exception exception) {
            throw new IOException("이미지 저장소를 준비하지 못했습니다.", exception);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
