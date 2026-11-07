package com.moshanghuakai.exhibition.config;

import com.moshanghuakai.exhibition.common.BusinessException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 展示文案原子热更新；运行时读取失败保留最后有效快照，不返回半份配置。 @author MoShangHuaKai */
@Slf4j
@Service
public class DisplayConfiguration {
  private final Path file;
  private volatile Map<String, String> current;

  public DisplayConfiguration(ExhibitionProperties p) {
    file = ApplicationPaths.resolve(p.getDisplayFile());
  }

  @PostConstruct
  public void initialize() {
    current = read();
  }

  @Scheduled(fixedDelay = 5000)
  public void reload() {
    try {
      current = read();
    } catch (RuntimeException e) {
      log.warn("展厅文案未更新：{}", e.getMessage());
    }
  }

  public Map<String, String> get() {
    return current;
  }

  private Map<String, String> read() {
    try (InputStream input = Files.newInputStream(file)) {
      if (Files.size(file) > 16384) throw new BusinessException("展厅文案文件超过16KB");
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      Object raw = new Yaml(new SafeConstructor(options)).load(input);
      if (!(raw instanceof Map)) throw new BusinessException("展厅配置必须是 YAML 对象");
      Object section = ((Map<?, ?>) raw).get("exhibition");
      if (!(section instanceof Map)) throw new BusinessException("缺少 exhibition 配置节点");
      Map<?, ?> values = (Map<?, ?>) section;
      String name = text(values.get("name"), 100),
          welcome = text(values.get("welcome-message"), 500);
      Map<String, String> result = new LinkedHashMap<>();
      result.put("hallName", name);
      result.put("welcomeMessage", welcome);
      return Collections.unmodifiableMap(result);
    } catch (Exception e) {
      throw new BusinessException("展厅文案读取失败：" + e.getMessage());
    }
  }

  private String text(Object value, int max) {
    if (!(value instanceof String)
        || ((String) value).trim().isEmpty()
        || ((String) value).length() > max) throw new BusinessException("展厅名称/欢迎语缺失或超过长度限制");
    return ((String) value).trim();
  }
}
