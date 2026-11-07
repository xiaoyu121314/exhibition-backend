package com.moshanghuakai.exhibition.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 通过实际JPEG像素验证EXIF方向和缩放，不依赖现场设备、原始人员照片或数据库。 @author MoShangHuaKai */
class StorageServiceTest {
  private final StorageService storage = new StorageService(new ExhibitionProperties(), null);
  private static final Color[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
  private static final int[][] CORNERS = {
      {0, 1, 2, 3}, {1, 0, 3, 2}, {3, 2, 1, 0}, {2, 3, 0, 1},
      {0, 2, 1, 3}, {2, 0, 3, 1}, {3, 1, 2, 0}, {1, 3, 0, 2}};

  @Test
  void correctsAllEightExifOrientationsWithEitherByteOrder() throws Exception {
    byte[] original = quadrants(80, 40);
    for (ByteOrder order : new ByteOrder[]{ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
      for (int orientation = 1; orientation <= 8; orientation++) {
        byte[] source = withExif(original, exif(orientation, order));
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(storage.normalizeStaffImage(source)));
        assertThat(normalized.getWidth()).as("width, orientation=%s, order=%s", orientation, order)
            .isEqualTo(orientation >= 5 ? 40 : 80);
        assertThat(normalized.getHeight()).isEqualTo(orientation >= 5 ? 80 : 40);
        assertCorners(normalized, CORNERS[orientation - 1]);
      }
    }
  }

  @Test
  void correctsMirroredPhonePortraitWhileKeepingTheThreeHundredPixelLimit() throws Exception {
    byte[] source = withExif(quadrants(800, 400), exif(5, ByteOrder.LITTLE_ENDIAN));
    byte[] normalized = storage.normalizeStaffImage(source);
    BufferedImage result = ImageIO.read(new ByteArrayInputStream(normalized));
    assertThat(result.getWidth()).isEqualTo(150);
    assertThat(result.getHeight()).isEqualTo(300);
    assertCorners(result, CORNERS[4]);
    // 输出像素已正向且不携带原EXIF，再次读取转码不能重复旋转或镜像。
    BufferedImage again = ImageIO.read(new ByteArrayInputStream(storage.normalizeStaffImage(normalized)));
    assertThat(again.getWidth()).isEqualTo(150);
    assertThat(again.getHeight()).isEqualTo(300);
    assertCorners(again, CORNERS[4]);
  }

  @Test
  void missingInvalidAndOutOfBoundsExifDoesNotRotateOrReadOutsideTheSegment() throws Exception {
    byte[] original = quadrants(80, 40);
    byte[] malformed = exif(6, ByteOrder.LITTLE_ENDIAN);
    ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN).putInt(10, -1);
    for (byte[] source : new byte[][]{original,
        withExif(original, exif(9, ByteOrder.BIG_ENDIAN)), withExif(original, malformed),
        withExif(original, new byte[]{'E', 'x', 'i', 'f', 0, 0})}) {
      BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(storage.normalizeStaffImage(source)));
      assertThat(normalized.getWidth()).isEqualTo(80);
      assertThat(normalized.getHeight()).isEqualTo(40);
      assertCorners(normalized, CORNERS[0]);
    }
  }

  private static byte[] quadrants(int width, int height) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++)
        image.setRGB(x, y, COLORS[(y >= height / 2 ? 2 : 0) + (x >= width / 2 ? 1 : 0)].getRGB());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", output);
    return output.toByteArray();
  }

  /** APP1中的TIFF主目录只含Orientation SHORT字段，使用标准12字节目录项。 */
  private static byte[] exif(int orientation, ByteOrder order) {
    ByteBuffer data = ByteBuffer.allocate(32).order(order);
    data.put(new byte[]{'E', 'x', 'i', 'f', 0, 0});
    data.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
    data.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
    data.putShort((short) 42).putInt(8).putShort((short) 1);
    data.putShort((short) 0x0112).putShort((short) 3).putInt(1);
    data.putShort((short) orientation).putShort((short) 0).putInt(0);
    return data.array();
  }

  private static byte[] withExif(byte[] jpeg, byte[] exif) throws IOException {
    int offset = 2;
    if ((jpeg[2] & 0xff) == 0xff && (jpeg[3] & 0xff) == 0xe0)
      offset = 4 + ((jpeg[4] & 0xff) << 8 | jpeg[5] & 0xff);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.write(jpeg, 0, offset);
    output.write(0xff); output.write(0xe1);
    output.write((exif.length + 2) >> 8); output.write((exif.length + 2) & 0xff);
    output.write(exif); output.write(jpeg, offset, jpeg.length - offset);
    return output.toByteArray();
  }

  private static void assertCorners(BufferedImage image, int[] expected) {
    for (int i = 0; i < 4; i++) {
      Color actual = new Color(image.getRGB(image.getWidth() * (i % 2 == 0 ? 1 : 3) / 4,
          image.getHeight() * (i < 2 ? 1 : 3) / 4));
      Color color = COLORS[expected[i]];
      assertThat(Math.abs(actual.getRed() - color.getRed())).isLessThan(20);
      assertThat(Math.abs(actual.getGreen() - color.getGreen())).isLessThan(20);
      assertThat(Math.abs(actual.getBlue() - color.getBlue())).isLessThan(20);
    }
  }
}
