package com.moshanghuakai.exhibition;

import com.moshanghuakai.exhibition.config.ApplicationPaths;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 展厅后台启动入口。任务状态持久化；中断的设备维护需人工核对，不能自动重发。 @author MoShangHuaKai */
@Slf4j
// 接口直接访问，不自动创建默认用户或生成登录密码。
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(ExhibitionProperties.class)
@MapperScan("com.moshanghuakai.exhibition.persistence")
public class ExhibitionApplication {
  public static void main(String[] args) {

    // 启动Spring上下文
    SpringApplication application = new SpringApplication(ExhibitionApplication.class);
    // 在加载 YAML 之前指定绝对位置；从其他目录执行 java -jar 也只读取 JAR 同级 config。
    // 命令行显式指定 spring.config.location 时仍可覆盖此默认值。
    String externalConfig = ApplicationPaths.directory().resolve("config").toUri().toString();
    if (!externalConfig.endsWith("/")) externalConfig += "/";
    String configLocation = "classpath:/,optional:" + externalConfig;
    application.setDefaultProperties(
        Collections.singletonMap("spring.config.location", configLocation));
    log.info(
        "应用目录：{}，外部配置目录：{}",
        ApplicationPaths.directory(),
        ApplicationPaths.directory().resolve("config"));
    ConfigurableApplicationContext context = application.run(args);
    // 获取环境对象
    Environment environment = context.getEnvironment();
    // 获取yml里面的端口号 (server.port)
    String port = environment.getProperty("server.port");

    log.info(
        "    \n\n  (♥◠‿◠)ﾉﾞ  系统启动成功,端口号："
            + port
            + "   ლ(´ڡ`ლ)ﾞ  \n\n      (♥◠‿◠)ﾉﾞ  小飞棍来咯！！！！   ლ(´ڡ`ლ)ﾞ\n");
  }
}
