package com.scg.alumni.infrastructure.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 원본 그대로 올리기에는 너무 큰 사진을 저장 직전에 줄인다.
 *
 * <p>임원 상당수가 휴대폰 카메라로 찍은 고해상도 원본을 그대로 올리기 때문에,
 * 크기 초과를 오류로 돌려보내는 대신 서버가 알아서 변환한다. 총동창회 회의에서
 * "10MB가 넘으면 자동으로 변환해 올라간다"고 합의한 동작이다.
 *
 * <p>변환 대상이 아닌 경우(임계값 이하, 애니메이션 GIF, JDK가 못 읽는 형식)에는
 * 원본 바이트를 그대로 돌려준다. 판단은 호출자가 아니라 이 클래스가 한다.
 */
@Slf4j
@Component
public class ImageCompressor {

    /** 이 크기를 넘으면 변환한다. 넘지 않으면 원본을 그대로 저장한다. */
    public static final long COMPRESSION_THRESHOLD_BYTES = 10L * 1024 * 1024;

    /** 변환 후 목표 크기. 품질을 낮춰가며 이 아래로 맞춘다. */
    private static final long TARGET_BYTES = 4L * 1024 * 1024;

    /** 변환 시 긴 변의 최대 픽셀. 게시글·프로필 어디에 써도 충분한 해상도다. */
    private static final int MAX_EDGE_PIXELS = 2400;

    private static final float[] QUALITY_STEPS = {0.85f, 0.75f, 0.65f, 0.55f};

    public record Result(byte[] bytes, String contentType, boolean compressed) {
        public long size() {
            return bytes.length;
        }
    }

    public Result compress(MultipartFile file, String contentType) throws IOException {
        return compress(file.getBytes(), contentType);
    }

    public Result compress(byte[] original, String contentType) throws IOException {
        String normalizedType = contentType.toLowerCase(Locale.ROOT);
        if (original.length <= COMPRESSION_THRESHOLD_BYTES) {
            return new Result(original, normalizedType, false);
        }
        // 애니메이션 GIF는 첫 프레임만 남기고 망가지므로 건드리지 않는다.
        if (MediaType.IMAGE_GIF_VALUE.equals(normalizedType)) {
            return new Result(original, normalizedType, false);
        }

        BufferedImage source = read(original);
        if (source == null) {
            // JDK ImageIO에 해당 포맷 플러그인이 없는 경우(예: webp). 원본을 그대로 둔다.
            log.warn("이미지를 디코딩하지 못해 변환을 건너뜁니다. contentType={}", normalizedType);
            return new Result(original, normalizedType, false);
        }

        BufferedImage scaled = scaleWithinMaxEdge(source);
        BufferedImage opaque = flattenTransparency(scaled);
        for (float quality : QUALITY_STEPS) {
            byte[] encoded = encodeJpeg(opaque, quality);
            if (encoded.length <= TARGET_BYTES) {
                return new Result(encoded, MediaType.IMAGE_JPEG_VALUE, true);
            }
        }
        // 마지막 단계까지 목표에 못 미쳐도 원본보다는 훨씬 작다.
        return new Result(encodeJpeg(opaque, QUALITY_STEPS[QUALITY_STEPS.length - 1]), MediaType.IMAGE_JPEG_VALUE, true);
    }

    private BufferedImage read(byte[] bytes) throws IOException {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            return ImageIO.read(input);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("이미지 디코딩 중 오류가 발생했습니다.", exception);
            return null;
        }
    }

    private BufferedImage scaleWithinMaxEdge(BufferedImage source) {
        int longestEdge = Math.max(source.getWidth(), source.getHeight());
        if (longestEdge <= MAX_EDGE_PIXELS) {
            return source;
        }
        double ratio = (double) MAX_EDGE_PIXELS / longestEdge;
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /** JPEG은 알파 채널을 표현하지 못한다. 투명 영역은 흰색으로 채운다. */
    private BufferedImage flattenTransparency(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, java.awt.Color.WHITE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(MediaType.IMAGE_JPEG_VALUE);
        if (!writers.hasNext()) {
            throw new IOException("JPEG 인코더를 찾을 수 없습니다.");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
