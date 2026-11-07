package com.moshanghuakai.exhibition.common;

import com.moshanghuakai.exhibition.config.ApplicationPaths;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.domain.Models.Media;
import com.moshanghuakai.exhibition.persistence.MediaMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Iterator;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 受控文件存储，文件名由服务端生成；数据库事务回滚时清理本次新文件。 @author MoShangHuaKai */
@Slf4j
@Service
public class StorageService {
  /** P6S 脸库指导要求 YUV 4:2:0 的人脸照片不超过300×300；抓拍和背景图不受此缩放规则影响。 */
  public static final int STAFF_IMAGE_MAX_SIDE = 300;
  private final Path root;
  private final MediaMapper mapper;

  public StorageService(ExhibitionProperties p, MediaMapper mapper) {
    this.root = ApplicationPaths.resolve(p.getStorageDirectory());
    this.mapper = mapper;
  }

  public Media saveImage(byte[] bytes, String type) {
    return save("STAFF".equals(type) ? normalizeStaffImage(bytes) : normalizeImage(bytes),
        type, "image/jpeg", ".jpg");
  }

  /** 单张上传和Excel预检共用：先按比例缩小，再编码RGB JPEG；不裁剪、不放大小图。 */
  public byte[] normalizeStaffImage(byte[] bytes) {
    return normalizeImage(bytes, STAFF_IMAGE_MAX_SIDE);
  }

  /**
   * 单张上传及导入在 normalizeStaffImage 通过后保存同一份JPEG字节，不再重复解码压缩。
   * 避免设备已接收照片后再次压缩，导致设备照片与本地文件内容不一致。
   */
  public Media savePreparedStaffImage(byte[] normalized) {
    if (normalized == null || normalized.length == 0 || normalized.length > 5 * 1024 * 1024)
      throw new BusinessException("照片为空或超过5MB");
    return save(normalized, "STAFF", "image/jpeg", ".jpg");
  }

  public Media saveWorkbook(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > 50 * 1024 * 1024)
      throw new BusinessException("Excel 文件为空或超过50MB");
    return save(
        bytes,
        "IMPORT",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".xlsx");
  }

  private Media save(byte[] bytes, String owner, String contentType, String extension) {
    Media media = new Media();
    media.setId(Ids.next());
    media.setOwnerType(owner);
    media.setContentType(contentType);
    media.setCreatedAt(LocalDateTime.now());
    media.setRelativePath(
        owner.toLowerCase() + "/" + LocalDate.now() + "/" + media.getId() + extension);
    Path path = resolve(media);
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    } catch (IOException e) {
      throw new BusinessException("文件保存失败，请检查存储目录权限及剩余空间");
    }
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED) removeFile(path);
            }
          });
    try {
      mapper.insert(media);
    } catch (RuntimeException e) {
      removeFile(path);
      throw e;
    }
    return media;
  }
  /** 先读取尺寸再解码，避免小压缩文件展开成巨幅图片；重编码只保留像素，不携带原图附加数据。 */
  public byte[] normalizeImage(byte[] bytes) {
    return normalizeImage(bytes, 0);
  }

  private byte[] normalizeImage(byte[] bytes, int maxSide) {
    if (bytes == null || bytes.length == 0 || bytes.length > 5 * 1024 * 1024)
      throw new BusinessException("照片为空或超过5MB");
    // 上传内容已限制在5MB内，使用本次调用的内存缓存，避免ImageIO默认磁盘缓存产生临时文件IO。
    // 不修改ImageIO全局缓存开关，其他图片处理调用仍可自行选择缓存策略。
    try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new BusinessException("只支持有效的 JPEG/PNG 图片");
      ImageReader reader = readers.next();
      try {
        String format = reader.getFormatName();
        if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format))
          throw new BusinessException("只支持 JPEG/PNG 图片");
        reader.setInput(input);
        int width = reader.getWidth(0), height = reader.getHeight(0);
        if (width < 20 || height < 20 || (long) width * height > 16000000L)
          throw new BusinessException("图片尺寸无效：最小20像素，最大1600万像素");
        // 手机JPEG的原始像素可能横置，浏览器依靠EXIF显示正向；转码前必须将方向落实到像素，
        // 否则重编码去掉EXIF后，设备收到的照片会旋转或镜像，导致人脸检测失败。
        int orientation = imageOrientation(reader);
        boolean swapAxes = orientation >= 5;
        // 缩放只作用于工作人员照片；先按原图完成像素上限校验，避免解码超大图片。
        double scale = maxSide == 0 ? 1 : Math.min(1, (double) maxSide / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        // 解码时先采样，避免为300像素头像分配整张1600万像素原图；保留约两倍目标分辨率，
        // 再用双三次插值精确缩放，兼顾人脸细节与内存占用。抓拍等原尺寸图片的采样率保持1。
        ImageReadParam param = reader.getDefaultReadParam();
        int subsampling = Math.max(1, Math.min(width / targetWidth, height / targetHeight) / 2);
        param.setSourceSubsampling(subsampling, subsampling, 0, 0);
        BufferedImage decoded = reader.read(0, param),
            rgb = new BufferedImage(swapAxes ? targetHeight : targetWidth,
                swapAxes ? targetWidth : targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = rgb.createGraphics();
        try {
          graphics.setColor(java.awt.Color.WHITE);
          graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
          graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
              java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          graphics.transform(orientationTransform(orientation, targetWidth, targetHeight));
          graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
        } finally {
          graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // JPEG编码同样使用局部内存缓存，输出前显式刷新，保证落盘和发送设备使用完整字节。
        try (ImageOutputStream encoded = new MemoryCacheImageOutputStream(output)) {
          ImageIO.write(rgb, "jpg", encoded);
          encoded.flush();
        }
        return output.toByteArray();
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new BusinessException("图片解码失败");
    }
  }

  /** 仅读取JPEG APP1中的EXIF主目录方向；不保留GPS等其他元数据，也不访问EXIF外部偏移。 */
  private static int imageOrientation(ImageReader reader) throws IOException {
    javax.imageio.metadata.IIOMetadata metadata = reader.getImageMetadata(0);
    if (metadata == null || !"javax_imageio_jpeg_image_1.0".equals(metadata.getNativeMetadataFormatName())) return 1;
    org.w3c.dom.Element root = (org.w3c.dom.Element) metadata.getAsTree(metadata.getNativeMetadataFormatName());
    org.w3c.dom.NodeList markers = root.getElementsByTagName("unknown");
    for (int i = 0; i < markers.getLength(); i++) {
      javax.imageio.metadata.IIOMetadataNode node = (javax.imageio.metadata.IIOMetadataNode) markers.item(i);
      if (!"225".equals(node.getAttribute("MarkerTag")) || !(node.getUserObject() instanceof byte[])) continue;
      byte[] data = (byte[]) node.getUserObject();
      if (data.length < 14 || data[0] != 'E' || data[1] != 'x' || data[2] != 'i' || data[3] != 'f'
          || data[4] != 0 || data[5] != 0) continue;
      ByteBuffer tiff = ByteBuffer.wrap(data, 6, data.length - 6).slice();
      int order = tiff.getShort(0) & 0xffff;
      if (order != 0x4949 && order != 0x4d4d) continue;
      tiff.order(order == 0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
      if ((tiff.getShort(2) & 0xffff) != 42) continue;
      // 所有偏移、目录条数均先检查边界，损坏或不支持的EXIF按普通图片处理，避免越界读取。
      long offset = Integer.toUnsignedLong(tiff.getInt(4));
      if (offset < 8 || offset > tiff.limit() - 2) continue;
      int directory = (int) offset;
      int count = tiff.getShort(directory) & 0xffff;
      if (count > (tiff.limit() - directory - 2) / 12) continue;
      for (int entry = directory + 2, j = 0; j < count; j++, entry += 12) {
        if ((tiff.getShort(entry) & 0xffff) == 0x0112 && (tiff.getShort(entry + 2) & 0xffff) == 3
            && tiff.getInt(entry + 4) == 1) {
          int value = tiff.getShort(entry + 8) & 0xffff;
          return value >= 1 && value <= 8 ? value : 1;
        }
      }
    }
    return 1;
  }

  /** EXIF共8种方向，包含镜像；变换与缩放合并绘制一次，避免旋转后再进行一次JPEG压缩。 */
  private static java.awt.geom.AffineTransform orientationTransform(int orientation, int width, int height) {
    switch (orientation) {
      case 2: return new java.awt.geom.AffineTransform(-1, 0, 0, 1, width, 0);
      case 3: return new java.awt.geom.AffineTransform(-1, 0, 0, -1, width, height);
      case 4: return new java.awt.geom.AffineTransform(1, 0, 0, -1, 0, height);
      case 5: return new java.awt.geom.AffineTransform(0, 1, 1, 0, 0, 0);
      case 6: return new java.awt.geom.AffineTransform(0, 1, -1, 0, height, 0);
      case 7: return new java.awt.geom.AffineTransform(0, -1, -1, 0, height, width);
      case 8: return new java.awt.geom.AffineTransform(0, -1, 1, 0, 0, width);
      default: return new java.awt.geom.AffineTransform();
    }
  }

  public Media require(String id) {
    Media media = mapper.find(id);
    if (media == null) throw new BusinessException("文件不存在或已到期");
    return media;
  }

  public byte[] read(String id) {
    try {
      return Files.readAllBytes(resolve(require(id)));
    } catch (IOException e) {
      throw new BusinessException("文件缺失，请重新上传或联系管理员");
    }
  }

  public Path resolve(Media media) {
    Path path = root.resolve(media.getRelativePath()).normalize();
    if (!path.startsWith(root)) throw new BusinessException("非法文件路径");
    return path;
  }

  public void delete(Media media) {
    try {
      Files.deleteIfExists(resolve(media));
      mapper.delete(media.getId());
    } catch (IOException e) {
      log.warn("文件清理暂未完成，稍后重试：{}", media.getId());
    }
  }

  private void removeFile(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("事务回滚文件清理失败：{}", path.getFileName());
    }
  }

  /** 补偿进程在写文件后、数据库提交前退出的情况；只删除存储根内已满一天且无元数据的系统文件。 */
  public void cleanupOrphanFiles(LocalDateTime before) {
    if (!Files.isDirectory(root)) return;
    long cutoff = before.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    try (java.util.stream.Stream<Path> files = Files.walk(root, 4)) {
      files
          .filter(path -> Files.isRegularFile(path) && !Files.isSymbolicLink(path))
          .forEach(
              path -> {
                String name = path.getFileName().toString();
                if (!name.matches("[0-9a-f-]{36}\\.(jpg|xlsx)")) return;
                try {
                  if (Files.getLastModifiedTime(path).toMillis() < cutoff
                      && mapper.find(name.substring(0, 36)) == null) {
                    Files.deleteIfExists(path);
                  }
                } catch (IOException e) {
                  log.warn("孤立文件暂未清理：{}", name);
                }
              });
    } catch (IOException e) {
      log.warn("存储目录扫描失败，保留下次清理");
    }
  }
}
