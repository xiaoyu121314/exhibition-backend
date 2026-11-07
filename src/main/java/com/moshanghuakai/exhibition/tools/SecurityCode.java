package com.moshanghuakai.exhibition.tools;

import com.moshanghuakai.exhibition.config.ApplicationPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

public class SecurityCode {

    /**
     * 获取当前系统时间并返回格式化后的字符串
     *
     * @param format 时间格式，例如 "yyyy-MM-dd'T'HH:mm:ss"
     * @return 格式化后的系统时间字符串
     */
    public static String getFormattedSystemTime(String format) {
        // 默认格式
        String dateTimeFormat = (format == null || format.isEmpty()) ? "yyyy-MM-dd'T'HH:mm:ss" : format;

        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateTimeFormat);
        return now.format(formatter);
    }

    /**
     * 读取配置中第一台摄像头/录像机的序列号，供单设备场景直接使用。
     * 序列号来自 application-exhibition.yml，不再使用固定的模拟值。
     *
     * @return 配置的设备序列号
     */
    public static String getUniqueCodeString() {
        return getUniqueCodeString(0);
    }

    /**
     * 按配置顺序读取设备序列号；多设备时由调用方明确选择，避免为错误设备生成安全码。
     * 此方法只加载 YAML，不启动 Spring 容器，也不连接数据库。
     *
     * @param deviceIndex exhibition.devices 列表下标，从 0 开始
     * @return 去除首尾空白后的设备序列号
     * @throws IllegalArgumentException 设备下标为负数
     * @throws IllegalStateException 对应设备的序列号未配置或为空
     */
    public static String getUniqueCodeString(int deviceIndex) {
        return getUniqueCodeString(deviceIndex, ApplicationPaths.directory());
    }

    /** 按应用运行目录定位配置，便于在不同部署目录下复用并验证读取逻辑。 */
    static String getUniqueCodeString(int deviceIndex, Path applicationDirectory) {
        if (deviceIndex < 0) {
            throw new IllegalArgumentException("设备下标不能小于 0");
        }

        // 部署时优先读取 JAR 同级 config 中的配置；IDE 运行时默认读取类路径资源。
        // 外部文件存在时以该文件为准，缺少序列号应报错，避免误用内置配置中的其他设备。
        Path externalConfig = applicationDirectory.resolve("config/application-exhibition.yml");
        Resource resource = Files.exists(externalConfig)
                ? new FileSystemResource(externalConfig.toFile())
                : new ClassPathResource("application-exhibition.yml");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        Properties properties = yaml.getObject();
        String propertyName = "exhibition.devices[" + deviceIndex + "].serial-number";
        String serialNumber = properties == null ? null : properties.getProperty(propertyName);
        if (serialNumber == null || serialNumber.trim().isEmpty()) {
            throw new IllegalStateException("未配置设备序列号：" + propertyName);
        }
        return serialNumber.trim();
    }


    public static int GenerateSafetyCode(String uniqueCode,String systemTime, StringBuilder pSafetyCode) {
        int IDVR_OK = 0;

        if (pSafetyCode == null || pSafetyCode.capacity() <= 0) {
            return -1;
        }

        try {
            // 计算 MD5
            String srcBuf = uniqueCode + systemTime;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] md5Bytes = md.digest(srcBuf.getBytes(StandardCharsets.UTF_8));

            // 转换 MD5 为字符串
            StringBuilder md5Str = new StringBuilder();
            for (byte b : md5Bytes) {
                md5Str.append(String.format("%02x", b));
            }

            // 截取前8位并反转前4位和后4位
            String partialMd5 = md5Str.substring(0, 8);
            char[] chars = partialMd5.toCharArray();
            for (int i = 0; i < 4; i++) {
                char temp = chars[i];
                chars[i] = chars[7 - i];
                chars[7 - i] = temp;
            }

            // 写入安全码
            String finalCode = new String(chars);
            pSafetyCode.replace(0, pSafetyCode.capacity(), finalCode);

            return IDVR_OK;

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return -1;
        }
    }


    public static void main(String[] args) {

        String systemTime = getFormattedSystemTime("yyyy-MM-dd'T'HH:mm:ss");
        System.out.println("System Time: " + systemTime);

        //获取唯一码字符串
        // 默认读取第一台设备；命令行可传入设备下标，例如 1 表示第二台设备。
        int deviceIndex = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        String uniqueCode = getUniqueCodeString(deviceIndex);
        System.out.println("Unique Code: " + uniqueCode);


        //生成安全码
        StringBuilder pSafetyCode = new StringBuilder(64);
        GenerateSafetyCode(uniqueCode,systemTime, pSafetyCode);
        System.out.println("Safety Code: " + pSafetyCode);
    }
}
