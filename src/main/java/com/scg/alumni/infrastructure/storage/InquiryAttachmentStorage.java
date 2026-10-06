package com.scg.alumni.infrastructure.storage;

import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 문의에 붙는 첨부 파일을 검사하고 저장소에 둔다.
 *
 * <p>사진 업로드와 달리 형식을 가리지 않는다(견적서·스크린샷·한글 문서가 섞여 온다).
 * 대신 브라우저가 열어서 실행할 수 있는 형식과 실행 파일은 받지 않는다. 사무처가 내려받아
 * 여는 파일이라, 받아 두기만 해도 위험한 것을 걸러낸다.
 *
 * <p>내려받을 때는 언제나 첨부(attachment)로 내려 보내고 형식 추측을 막는다. 저장소의 파일을
 * 화면 안에서 그대로 열어 보이게 하면, 올린 사람이 만든 스크립트가 관리자 화면 권한으로 돈다.
 */
@Component
@RequiredArgsConstructor
public class InquiryAttachmentStorage {

    public static final int MAX_FILES = 5;
    public static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            "exe", "bat", "cmd", "com", "scr", "msi", "dll", "sh", "ps1", "vbs", "js", "mjs", "jar", "apk",
            "html", "htm", "xhtml", "svg", "php", "jsp", "asp", "aspx", "hta", "lnk", "reg");

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    /** 저장된 첨부의 정보. */
    public record Stored(String originalName, String objectName, String contentType, long size) {
    }

    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "첨부할 파일을 선택해주세요.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "첨부 파일은 개당 50MB 이하만 올릴 수 있습니다.");
        }
        String originalName = cleanName(file.getOriginalFilename());
        if (BLOCKED_EXTENSIONS.contains(extensionOf(originalName))) {
            throw new ResponseStatusException(BAD_REQUEST, "보안상 올릴 수 없는 파일 형식입니다. 압축(zip)해서 올려주세요.");
        }
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        String objectName = "inquiries/" + UUID.randomUUID();
        try (InputStream input = file.getInputStream()) {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectName)
                    .contentType(contentType)
                    .stream(input, file.getSize(), -1L)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("첨부 파일 저장에 실패했습니다.", exception);
        }
        return new Stored(originalName, objectName, contentType, file.getSize());
    }

    /** 내려받기 응답. 전체를 메모리에 올리지 않고 흘려 보낸다 — 파일이 50MB 까지 간다. */
    public ResponseEntity<StreamingResponseBody> download(String objectName, String fileName, long size) {
        // 열기 전에 존재를 확인해 둔다. 스트림을 연 뒤에는 상태 코드를 바꿀 수 없다.
        InputStream input;
        try {
            input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.bucket())
                    .object(objectName)
                    .build());
        } catch (io.minio.errors.ErrorResponseException exception) {
            throw new ResponseStatusException(NOT_FOUND, "첨부 파일을 찾을 수 없습니다.");
        } catch (Exception exception) {
            throw new IllegalStateException("첨부 파일을 불러오지 못했습니다.", exception);
        }
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(body);
    }

    /** 경로 문자와 제어 문자를 지우고 길이를 줄인다. 이 이름은 표시와 내려받기 이름으로만 쓴다. */
    static String cleanName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            name = "첨부파일";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioProperties.bucket()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(minioProperties.bucket()).build());
        }
    }
}
