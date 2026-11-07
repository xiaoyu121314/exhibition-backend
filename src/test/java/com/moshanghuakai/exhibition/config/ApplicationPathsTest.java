package com.moshanghuakai.exhibition.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证部署目录与启动工作目录无关，并覆盖带空格的 JAR 路径和 IDEA 运行。 @author MoShangHuaKai */
class ApplicationPathsTest {
  @TempDir Path temporary;

  @Test
  void jarUsesItsOwnDirectory() throws Exception {
    Path release = Files.createDirectories(temporary.resolve("release app"));
    Path jar = Files.createFile(release.resolve("exhibition.jar"));
    assertThat(ApplicationPaths.directoryOf(jar.toFile())).isEqualTo(release);
    assertThat(ApplicationPaths.resolve(release, "./config/exhibition-display.yml"))
        .isEqualTo(release.resolve("config/exhibition-display.yml"));
    assertThat(ApplicationPaths.resolve(release, "./data")).isEqualTo(release.resolve("data"));
  }

  @Test
  void absolutePathsArePreserved() {
    Path absolute = temporary.resolve("external storage").toAbsolutePath();
    assertThat(ApplicationPaths.resolve(temporary.resolve("jar"), absolute.toString()))
        .isEqualTo(absolute);
  }

  @Test
  void developmentUsesProjectDirectory() throws Exception {
    Path project = Files.createDirectories(temporary.resolve("project"));
    Files.createFile(project.resolve("pom.xml"));
    Path classes = Files.createDirectories(project.resolve("target/classes"));
    assertThat(ApplicationPaths.directoryOf(classes.toFile())).isEqualTo(project);
  }
}
