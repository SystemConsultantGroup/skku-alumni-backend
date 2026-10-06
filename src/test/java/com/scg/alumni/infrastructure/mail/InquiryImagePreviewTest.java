package com.scg.alumni.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InquiryImagePreviewTest {

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        return image;
    }

    /** 사진 한 장이 수 MB 라 그대로 붙이면 메일이 반송된다. 본문 폭에 맞춰 줄이고 비율은 지킨다. */
    @Test
    void wideImagesAreShrunkToTheMailWidth() throws Exception {
        byte[] thumbnail = InquiryImagePreview.thumbnailOf(encode(solid(2000, 1000, Color.BLUE), "png")).orElseThrow();

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(result.getWidth()).isEqualTo(InquiryImagePreview.MAX_WIDTH);
        assertThat(result.getHeight()).isEqualTo(InquiryImagePreview.MAX_WIDTH / 2);
    }

    @Test
    void smallImagesAreNotEnlarged() throws Exception {
        byte[] thumbnail = InquiryImagePreview.thumbnailOf(encode(solid(120, 80, Color.RED), "png")).orElseThrow();

        BufferedImage result = ImageIO.read(new ByteArrayInputStream(thumbnail));
        assertThat(result.getWidth()).isEqualTo(120);
        assertThat(result.getHeight()).isEqualTo(80);
    }

    /** 투명 PNG 를 JPEG 로 바꾸면 투명한 곳이 검게 변한다. */
    @Test
    void transparentPixelsBecomeWhite() throws Exception {
        BufferedImage transparent = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        byte[] thumbnail = InquiryImagePreview.thumbnailOf(encode(transparent, "png")).orElseThrow();

        int pixel = ImageIO.read(new ByteArrayInputStream(thumbnail)).getRGB(20, 20);
        assertThat(new Color(pixel).getRed()).isGreaterThan(240);
        assertThat(new Color(pixel).getBlue()).isGreaterThan(240);
    }

    /** 이미지가 아닌 바이트는 미리보기만 건너뛴다. 예외로 메일 발송을 막으면 안 된다. */
    @Test
    void nonImageBytesYieldNothing() {
        assertThat(InquiryImagePreview.thumbnailOf("<html><script>alert(1)</script></html>".getBytes())).isEmpty();
        assertThat(InquiryImagePreview.thumbnailOf(new byte[0])).isEmpty();
    }

    /** 세로로 찍은 사진은 눕혀서 저장되고 EXIF 가 "90도 돌려 보라"고 적는다. */
    @Test
    void exifRotationTurnsPortraitPhotosUpright() throws Exception {
        // 가로 300 x 세로 100 으로 저장된 사진 + EXIF orientation=6(시계 방향 90도)
        byte[] jpeg = withOrientation(encode(solid(300, 100, Color.GREEN), "jpg"), 6);

        assertThat(InquiryImagePreview.exifOrientation(jpeg)).isEqualTo(6);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(
                InquiryImagePreview.thumbnailOf(jpeg).orElseThrow()));
        assertThat(result.getWidth()).isEqualTo(100);
        assertThat(result.getHeight()).isEqualTo(300);
    }

    @Test
    void imagesAndVideosAreToldApartByExtension() {
        assertThat(InquiryImagePreview.isImage("사진.JPG")).isTrue();
        assertThat(InquiryImagePreview.isImage("문서.pdf")).isFalse();
        assertThat(InquiryImagePreview.isVideo("현장.MOV")).isTrue();
        assertThat(InquiryImagePreview.isVideo("사진.png")).isFalse();
    }

    /** SOI 바로 뒤에 big-endian EXIF APP1 조각(orientation 하나)을 끼워 넣는다. */
    private static byte[] withOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,                 // APP1, 길이 34
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,         // TIFF 헤더, IFD0 오프셋 8
                0x00, 0x01,                                           // 항목 1개
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,       // tag 0x0112, SHORT, count 1
                0x00, (byte) orientation, 0x00, 0x00,                 // 값
                0x00, 0x00, 0x00, 0x00                                // 다음 IFD 없음
        };
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }
}
