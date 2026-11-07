package com.moshanghuakai.exhibition.config;

import com.moshanghuakai.exhibition.ExhibitionApplication;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.system.ApplicationHome;

/**
 * 统一运行目录：部署时以 JAR 所在目录为基准，不依赖执行 java 命令时的工作目录。 IDE/测试运行时以包含 pom.xml 的项目目录为基准，保持相同的相对目录结构。
 *
 * @author MoShangHuaKai
 */
public final class ApplicationPaths {
  private static final Path DIRECTORY = locateDirectory();

  private ApplicationPaths() {}

  private static Path locateDirectory() {
    File source = new ApplicationHome(ExhibitionApplication.class).getSource();
    // Spring Boot 在 JUnit 调用栈中会主动清空 ApplicationHome.source。
    // 此时仍从启动类的实际代码位置定位，不能退回依赖工作目录的 user.dir。
    if (source == null) {
      try {
        java.net.URL location =
            ExhibitionApplication.class.getProtectionDomain().getCodeSource().getLocation();
        if ("file".equals(location.getProtocol())) {
          source = Paths.get(location.toURI()).toFile();
        }
      } catch (Exception e) {
        throw new IllegalStateException("无法解析启动类的实际文件位置", e);
      }
    }
    return directoryOf(source);
  }

  public static Path directory() {
    return DIRECTORY;
  }

  public static Path resolve(String configuredPath) {
    return resolve(DIRECTORY, configuredPath);
  }

  static Path resolve(Path directory, String configuredPath) {
    Path path = Paths.get(configuredPath);
    return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
  }

  /** ApplicationHome 会解开 Spring Boot 的嵌套 JAR 地址，空格和中文路径无需手工截断 URL。 */
  static Path directoryOf(File source) {
    if (source == null) {
      throw new IllegalStateException("无法定位应用目录，请通过可执行 JAR 或项目启动类运行");
    }
    Path location = source.toPath().toAbsolutePath().normalize();
    if (Files.isRegularFile(location)) {
      return location.getParent();
    }
    for (Path current = location; current != null; current = current.getParent()) {
      if (Files.isRegularFile(current.resolve("pom.xml"))) {
        return current;
      }
    }
    return location;
  }
}
