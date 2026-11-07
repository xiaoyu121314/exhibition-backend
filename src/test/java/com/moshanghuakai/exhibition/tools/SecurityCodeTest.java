package com.moshanghuakai.exhibition.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证安全码使用的设备序列号来自 YAML，并拒绝缺失或错误的设备选择。 @author MoShangHuaKai */
class SecurityCodeTest {
  @TempDir Path temporary;

  @Test
  void externalConfigurationTakesPrecedenceAndSupportsMultipleDevices() throws Exception {
    writeConfiguration(
        "exhibition:\n  devices:\n    - serial-number: ' CAMERA-001 '\n"
            + "    - serial-number: 'RECORDER-002'\n");

    assertThat(SecurityCode.getUniqueCodeString(0, temporary)).isEqualTo("CAMERA-001");
    assertThat(SecurityCode.getUniqueCodeString(1, temporary)).isEqualTo("RECORDER-002");
  }

  @Test
  void developmentReadsClasspathConfigurationWhenExternalFileIsAbsent() {
    assertThat(SecurityCode.getUniqueCodeString(0, temporary)).isNotBlank();
  }

  @Test
  void missingExternalSerialNumberDoesNotFallBackToBundledDevice() throws Exception {
    writeConfiguration("exhibition:\n  devices: []\n");

    assertThatIllegalStateException()
        .isThrownBy(() -> SecurityCode.getUniqueCodeString(0, temporary))
        .withMessageContaining("exhibition.devices[0].serial-number");
  }

  @Test
  void blankSerialNumberIsRejected() throws Exception {
    writeConfiguration("exhibition:\n  devices:\n    - serial-number: '  '\n");

    assertThatIllegalStateException()
        .isThrownBy(() -> SecurityCode.getUniqueCodeString(0, temporary))
        .withMessageContaining("exhibition.devices[0].serial-number");
  }

  @Test
  void unknownDeviceIndexIsRejected() throws Exception {
    writeConfiguration("exhibition:\n  devices:\n    - serial-number: 'CAMERA-001'\n");

    assertThatIllegalStateException()
        .isThrownBy(() -> SecurityCode.getUniqueCodeString(1, temporary))
        .withMessageContaining("exhibition.devices[1].serial-number");
  }

  @Test
  void negativeDeviceIndexIsRejected() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> SecurityCode.getUniqueCodeString(-1, temporary))
        .withMessage("设备下标不能小于 0");
  }

  /** 在隔离目录写入配置，验证实际 YAML 解析流程，避免修改项目或部署设备配置。 */
  private void writeConfiguration(String content) throws Exception {
    Path directory = Files.createDirectories(temporary.resolve("config"));
    Files.write(directory.resolve("application-exhibition.yml"), content.getBytes(StandardCharsets.UTF_8));
  }
}
