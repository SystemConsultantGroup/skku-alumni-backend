package com.scg.alumni.infrastructure.mail;

import com.scg.alumni.infrastructure.minio.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 문의 알림 메일에 끼워 넣을 이미지 미리보기를 만든다.
 *
 * <p>메일 속 이미지는 로그인할 수 없으니 주소로 걸 수 없다. 그래서 작게 줄인 JPEG 를 메일에
 * 직접 실어 보낸다(cid 인라인). 원본을 그대로 붙이면 사진 한 장이 몇 MB 라 메일이 반송된다.
 *
 * <p>올린 사람이 마음대로 만든 파일이 들어오므로 읽기 전에 크기와 화소 수를 먼저 본다.
 * 작은 PNG 한 장이 수억 화소로 풀려 서버 메모리를 먹는 일을 막기 위해서다. 읽지 못하거나
 * 너무 크면 미리보기만 건너뛰고 메일은 그대로 나간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InquiryImagePreview {

    /** 메일 본문 폭(600px)에서 좌우 여백을 뺀 값. */
    static final int MAX_WIDTH = 536;
    static final long MAX_SOURCE_BYTES = 15L * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000L;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "webm");

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;

    public static boolean isImage(String fileName) {
        return IMAGE_EXTENSIONS.contains(extensionOf(fileName));
    }

    public static boolean isVideo(String fileName) {
        return VIDEO_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** 저장소의 첨부에서 미리보기를 만든다. 만들 수 없으면 비어 있다. */
    public Optional<byte[]> thumbnail(String fileName, String objectName, long size) {
        if (!isImage(fileName) || size > MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                .bucket(minioProperties.bucket())
                .object(objectName)
                .build())) {
            return thumbnailOf(input.readNBytes((int) MAX_SOURCE_BYTES + 1));
        } catch (Exception exception) {
            log.warn("문의 첨부 미리보기를 만들지 못했습니다. file={}", fileName, exception);
            return Optional.empty();
        }
    }

    /** 이미지 바이트를 메일에 실을 크기의 JPEG 로 줄인다. */
    static Optional<byte[]> thumbnailOf(byte[] source) {
        if (source.length > MAX_SOURCE_BYTES) {
            return Optional.empty();
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PIXELS) {
                    return Optional.empty();
                }
                BufferedImage image = reader.read(0);
                image = rotate(image, exifOrientation(source));
                return Optional.of(encode(shrink(image)));
            } finally {
                reader.dispose();
            }
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static BufferedImage shrink(BufferedImage image) {
        int width = Math.min(image.getWidth(), MAX_WIDTH);
        int height = Math.max(1, Math.round(image.getHeight() * (width / (float) image.getWidth())));
        // 투명 PNG 는 JPEG 로 바꾸면 검게 변한다. 흰 바탕에 그린다.
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 한 번에 크게 줄이면 계단이 진다. 절반씩 줄여 가며 마지막에 맞춘다.
            BufferedImage step = image;
            while (step.getWidth() / 2 >= width * 1.0 && step.getWidth() / 2 > 0) {
                BufferedImage half = new BufferedImage(step.getWidth() / 2, Math.max(1, step.getHeight() / 2),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D halfGraphics = half.createGraphics();
                halfGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                halfGraphics.drawImage(step, 0, 0, half.getWidth(), half.getHeight(), null);
                halfGraphics.dispose();
                step = half;
            }
            graphics.drawImage(step, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            throw new java.io.IOException("JPEG 인코더가 없습니다.");
        }
        return output.toByteArray();
    }

    /**
     * 휴대폰 사진은 눕혀서 저장하고 EXIF 에 "이만큼 돌려서 보라"고 적는다. ImageIO 는 이를
     * 읽지 않아서, 그대로 줄이면 세로 사진이 옆으로 누운 채 메일에 실린다.
     */
    private static BufferedImage rotate(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) {
            return image;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform transform = new AffineTransform();
        switch (orientation) {
            case 2 -> { transform.translate(width, 0); transform.scale(-1, 1); }
            case 3 -> { transform.translate(width, height); transform.rotate(Math.PI); }
            case 4 -> { transform.translate(0, height); transform.scale(1, -1); }
            case 5 -> { transform.rotate(-Math.PI / 2); transform.scale(-1, 1); }
            case 6 -> { transform.translate(height, 0); transform.rotate(Math.PI / 2); }
            case 7 -> { transform.translate(height, width); transform.rotate(Math.PI / 2); transform.scale(-1, 1); }
            default -> { transform.translate(0, width); transform.rotate(-Math.PI / 2); }
        }
        BufferedImage out = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.drawImage(image, transform, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    /** JPEG 의 EXIF 회전 값(1~8). 없거나 읽을 수 없으면 1. */
    static int exifOrientation(byte[] data) {
        try {
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int pos = 2;
            while (pos + 4 <= data.length && (data[pos] & 0xFF) == 0xFF) {
                int marker = data[pos + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    return 1;
                }
                int length = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                if (marker == 0xE1 && length >= 16 && pos + 4 + 6 <= data.length
                        && data[pos + 4] == 'E' && data[pos + 5] == 'x' && data[pos + 6] == 'i' && data[pos + 7] == 'f') {
                    return orientationFromTiff(data, pos + 10, pos + 2 + length);
                }
                pos += 2 + length;
            }
        } catch (RuntimeException ignored) {
            // 깨진 EXIF 는 회전 없이 쓴다.
        }
        return 1;
    }

    private static int orientationFromTiff(byte[] data, int tiff, int end) {
        if (tiff + 8 > end || end > data.length) {
            return 1;
        }
        boolean little = data[tiff] == 'I';
        int ifd = tiff + (int) readInt(data, tiff + 4, 4, little);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = (int) readInt(data, ifd, 2, little);
        for (int index = 0; index < entries; index++) {
            int entry = ifd + 2 + index * 12;
            if (entry + 12 > end) {
                break;
            }
            if (readInt(data, entry, 2, little) == 0x0112) {
                int value = (int) readInt(data, entry + 8, 2, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static long readInt(byte[] data, int offset, int bytes, boolean little) {
        long value = 0;
        for (int index = 0; index < bytes; index++) {
            int b = data[offset + (little ? bytes - 1 - index : index)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
