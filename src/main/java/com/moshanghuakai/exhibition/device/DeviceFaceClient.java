package com.moshanghuakai.exhibition.device;

import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.common.StorageService;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.Device;
import com.moshanghuakai.exhibition.domain.Models.Staff;
import com.moshanghuakai.exhibition.tools.SecurityCode;
import java.io.StringReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** P6S 主动人员管理协议；只操作已有脸库，不修改设备拥有者或报警联动。 @author MoShangHuaKai */
@Slf4j
@Service
public class DeviceFaceClient {
  private final ExhibitionProperties properties;

  public DeviceFaceClient(ExhibitionProperties properties) {
    this.properties = properties;
  }

  /** 当前方案只有一个同步目标；拒绝多目标歧义，避免部分设备成功后误报全部成功。 */
  public Device target() {
    List<Device> targets = new ArrayList<>();
    for (Device device : properties.getDevices())
      if (!blank(device.getBaseUrl())) targets.add(device);
    if (targets.size() != 1) throw failure("请配置且仅配置一台工作人员同步设备的 base-url", false);
    Device device = targets.get(0);
    if (blank(device.getSerialNumber()) || blank(device.getUsername())
        || blank(device.getPassword()) || blank(device.getFaceGroupId())
        || blank(device.getOwnerIdentifier()))
      throw failure("请填写设备 username/password/face-group-id/owner-identifier 和序列号", false);
    try {
      URI uri = URI.create(device.getBaseUrl());
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null
          || uri.getQuery() != null || uri.getFragment() != null)
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw failure("设备 base-url 格式不正确", false);
    }
    if (device.getConnectTimeoutMs() <= 0 || device.getReadTimeoutMs() <= 0)
      throw failure("设备连接及读取超时必须大于0", false);
    return device;
  }

  @Data
  public static class Person {
    private String faceUuid, groupId, name, sex, certificateType, certificateId, country, address, birthday;
    private String personId, uniqueId, modelState;
  }

  /** 用 GroupID2 定位脸库，再使用其数字 GroupID 查询；不能假定所有设备的工作人员库都是0。 */
  public Map<String, Person> people(Device device) {
    Document groups = request(device, "/FaceGroups/QueryAll", HttpMethod.PUT, "", false);
    if (!"FaceGroups".equals(groups.getDocumentElement().getTagName()))
      throw failure("设备脸库查询响应格式错误", false);
    String groupId = null;
    NodeList nodes = groups.getElementsByTagName("FaceGroup");
    // HTTP 200 仅表示查询成功；空列表说明设备没有返回任何脸库，不能继续上传人员。
    // 与配置不匹配分开提示，避免现场反复更换照片、重试新增却始终停在查询阶段。
    if (nodes.getLength() == 0)
      throw failure("设备未返回任何脸库，请先在设备端恢复或创建工作人员脸库，再核对 face-group-id（GroupID2）", false);
    for (int i = 0; i < nodes.getLength(); i++) {
      Element group = (Element) nodes.item(i);
      if (device.getFaceGroupId().equals(value(group, "GroupID2").trim())) {
        if (groupId != null) throw failure("设备脸库ID重复，无法确认目标", false);
        groupId = value(group, "GroupID").trim();
      }
    }
    // 只按字符串 GroupID2 选择目标；不能退回第一条脸库或用数字 GroupID 代替配置。
    if (groupId == null)
      throw failure("配置的脸库不存在，请将 face-group-id 配置为设备工作人员脸库的 GroupID2（不是数字 GroupID）", false);
    if (!groupId.matches("[0-9]+"))
      throw failure("设备目标脸库缺少有效数字 GroupID，无法查询人员，请核对设备脸库数据", false);
    Map<String, Person> result = new LinkedHashMap<>();
    Set<String> nativePersonIds = new HashSet<>();
    for (int page = 1; page <= 101; page++) {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("GroupID", groupId);
      for (String field : Arrays.asList("Name", "Sex", "CertificateType", "CertificateID",
          "Country", "Address", "StartBirthday", "StopBirthday", "ModelState", "OrganizationID"))
        fields.put(field, "");
      fields.put("Pagesize", "100");
      fields.put("Pagenum", String.valueOf(page));
      Document document = request(device, "/FaceGroup/QueryPersonInfoList", HttpMethod.PUT,
          xml("PersonInfoFilter", fields), false);
      if (!"PersonInfoList".equals(document.getDocumentElement().getTagName()))
        throw failure("设备人员查询响应格式错误", false);
      NodeList entries = document.getElementsByTagName("PersonInfo");
      int nativeCount = 0;
      for (int i = 0; i < entries.getLength(); i++) {
        Element entry = (Element) entries.item(i);
        String faceUuid = value(entry, "FaceUUID").trim();
        if (blank(faceUuid)) {
          // 现场原生人员（如FaceVersion=0）有数字PersonID，但FaceUUID为空，不能作为UUID人员关联。
          // 只接受属于当前脸库且数字身份完整的记录；保留设备资料，快照仅收集真实FaceUUID。
          String personId = value(entry, "PersonID").trim();
          if (!personId.matches("[0-9]+") || !groupId.equals(value(entry, "GroupID").trim()))
            throw failure("设备人员FaceUUID为空且原生身份无效，页码 " + page, false);
          if (!nativePersonIds.add(personId))
            throw failure("设备原生人员ID查询分页重复，页码 " + page, false);
          nativeCount++;
          continue;
        }
        Person person = new Person();
        person.setFaceUuid(faceUuid);
        person.setPersonId(value(entry, "PersonID").trim());
        person.setUniqueId(value(entry, "UniqueID").trim());
        person.setModelState(value(entry, "ModelState").trim());
        // 删除接口需要数字 GroupID；使用本次查询确认的目标脸库，不把 GroupID2 当作数字 ID。
        person.setGroupId(groupId);
        person.setName(value(entry, "Name"));
        person.setSex(value(entry, "Sex"));
        String certificateType = value(entry, "CertificateType");
        person.setCertificateType(blank(certificateType) ? "none" : certificateType);
        person.setCertificateId(value(entry, "CertificateID"));
        person.setCountry(value(entry, "Country"));
        person.setAddress(value(entry, "Address"));
        person.setBirthday(value(entry, "Birthday"));
        if (result.put(person.getFaceUuid(), person) != null)
          throw failure("设备FaceUUID查询分页重复，页码 " + page, false);
      }
      if (nativeCount > 0)
        log.info("设备原生人员未分配FaceUUID，保留原记录，页码={}，数量={}", page, nativeCount);
      // 以设备原始条数判断是否还有下一页，不能因UUID快照未收录原生人员而提前结束分页。
      if (entries.getLength() < 100) return result;
    }
    throw failure("设备人员查询超过脸库容量限制", false);
  }

  /** 当前现场流程为新增显式分配 UUID；新增成功后仍回读唯一身份，不按姓名直接绑定。 */
  public void putPhoto(Device device, String faceUuid, Staff staff, byte[] photo) {
    requirePhotoSize(photo);
    Map<String, String> fields = authorized(device);
    fields.put("Name", staff.getName());
    fields.put("Sex", staff.getSex());
    fields.put("FaceUUID", faceUuid);
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    fields.forEach(body::add);
    HttpHeaders photoHeaders = new HttpHeaders();
    photoHeaders.setContentType(MediaType.IMAGE_JPEG);
    body.add("file", new HttpEntity<>(new ByteArrayResource(photo) {
      @Override public String getFilename() { return "face.jpg"; }
    }, photoHeaders));
    request(device, "/FaceGroup/UpdatePersonInfoAndFaceImage", HttpMethod.POST, body, true);
  }

  /** 发送前拦截此前已上传的超规格图片，提示重新上传；请求尚未发出，结果明确且无需设备补偿。 */
  private static void requirePhotoSize(byte[] photo) {
    try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(photo))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw failure("设备照片不是有效图片，请重新上传", false);
      ImageReader reader = readers.next();
      try {
        reader.setInput(input);
        if (Math.max(reader.getWidth(0), reader.getHeight(0)) > StorageService.STAFF_IMAGE_MAX_SIDE)
          throw failure("设备照片不能超过300×300，请重新上传照片后再保存工作人员", false);
      } finally { reader.dispose(); }
    } catch (java.io.IOException e) {
      throw failure("设备照片尺寸读取失败，请重新上传", false);
    }
  }

  /** 保留设备上未由后台维护的证件、国籍等字段，避免资料修改时将它们清空。 */
  public void modify(Device device, String faceUuid, Staff staff, Person existing) {
    Map<String, String> fields = authorized(device);
    fields.put("Name", staff.getName());
    fields.put("Sex", staff.getSex());
    fields.put("FaceUUID", faceUuid);
    fields.put("CertificateType", blank(existing.getCertificateType()) ? "none" : existing.getCertificateType());
    fields.put("CertificateID", existing.getCertificateId());
    fields.put("Country", existing.getCountry());
    fields.put("Address", existing.getAddress());
    fields.put("Birthday", existing.getBirthday());
    request(device, "/FaceGroup/ModifyPersonInfo", HttpMethod.PUT, xml("PersonInfo", fields), true);
  }

  public void delete(Device device, Person person) {
    Map<String, String> fields = authorized(device);
    // 现场固件要求两个数字身份字段同时存在；新版本按 FaceUUID 定位人员，PersonID 使用 0。
    fields.put("GroupID", person.getGroupId());
    fields.put("PersonID", "0");
    fields.put("FaceUUID", person.getFaceUuid());
    // 仅移出本系统配置的脸库，不删除其他脸库中的同一人员。
    fields.put("IsDeletePicture", "false");
    String body = "<PersonInfoList>" + xml("PersonInfo", fields) + "</PersonInfoList>";
    request(device, "/FaceGroup/DeletePersonList", HttpMethod.PUT, body, true);
  }

  private Map<String, String> authorized(Device device) {
    String time = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    StringBuilder code = new StringBuilder(64);
    if (SecurityCode.GenerateSafetyCode(device.getOwnerIdentifier(), time, code) != 0)
      throw failure("生成设备安全码失败", false);
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("Ownner", code.toString());
    fields.put("SystemTime", time);
    fields.put("FaceGroupID", device.getFaceGroupId());
    return fields;
  }

  /** 诊断仅允许固定的只读查询，不能通过此入口拼接任意设备路径或执行配置写入。 */
  public Map<String, String> recognitionDiagnostics() {
    Device device = target();
    int channel = properties.getRecognition().getChannelId();
    if (channel < 1) throw failure("识别通道必须大于0", false);
    Map<String, String> result = new LinkedHashMap<>();
    for (String path : Arrays.asList("/System/DeviceCap", "/System/AICap",
        "/FaceReco/" + channel + "/BaseConfig", "/FaceReco/" + channel + "/RecoRuleList", "/AI/FaceSnapshotCfg")) {
      try {
        Document document = request(device, path, HttpMethod.GET, "", false);
        javax.xml.transform.Transformer transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        java.io.StringWriter output = new java.io.StringWriter();
        transformer.transform(new javax.xml.transform.dom.DOMSource(document),
            new javax.xml.transform.stream.StreamResult(output));
        result.put(path, output.toString());
      } catch (DeviceSyncException e) {
        // 统一请求层已生成可展示的错误原因；逐项保留厂商码及路径，便于核对固件能力。
        result.put(path, e.getMessage());
      } catch (Exception e) { result.put(path, "查询失败或设备不支持，请核对通道及固件"); }
    }
    return result;
  }

  /** 设备有时以 text/plain 返回 XML，统一按文本解析；禁止实体、DTD和外部资源访问。 */
  private Document request(Device device, String path, HttpMethod method, Object body, boolean write) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(device.getConnectTimeoutMs());
    factory.setReadTimeout(device.getReadTimeoutMs());
    HttpHeaders headers = new HttpHeaders();
    headers.setBasicAuth(device.getUsername(), device.getPassword(), StandardCharsets.UTF_8);
    headers.setContentType(body instanceof MultiValueMap ? MediaType.MULTIPART_FORM_DATA
        : new MediaType("application", "xml", StandardCharsets.UTF_8));
    RestTemplate client = new RestTemplate(factory);
    if (body instanceof MultiValueMap) {
      useBrowserMultipart(client);
    }
    long started = System.nanoTime();
    // 路径均来自本类固定协议接口。只记录方法、阶段和耗时，不记录地址、凭据、人员或照片。
    log.info("调用人员同步设备，方法={}，接口={}，写入={}，连接超时={}ms，读取超时={}ms",
        method, path, write, device.getConnectTimeoutMs(), device.getReadTimeoutMs());
    String response;
    try {
      ResponseEntity<byte[]> reply = client.exchange(
          device.getBaseUrl().replaceAll("/+$", "") + path,
          method, new HttpEntity<>(body, headers), byte[].class);
      byte[] bytes = reply.getBody();
      response = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
      log.info("人员同步设备已响应，方法={}，接口={}，HTTP状态={}，耗时={}ms",
          method, path, reply.getStatusCodeValue(), elapsed(started));
    } catch (HttpStatusCodeException e) {
      log.warn("人员同步设备HTTP失败，方法={}，接口={}，HTTP状态={}，耗时={}ms",
          method, path, e.getRawStatusCode(), elapsed(started));
      throw failure("设备HTTP状态 " + e.getRawStatusCode() + "；接口=" + path,
          write && e.getStatusCode().is5xxServerError());
    } catch (RestClientException e) {
      // 根据异常类型分类，不能输出异常原文或堆栈，其中可能包含完整 URL 和设备响应。
      // 写请求超时仍表示结果不确定，保留查询与补偿流程，不能把超时当作设备明确拒绝。
      Throwable cause = e.getMostSpecificCause();
      String reason = cause instanceof SocketTimeoutException ? "设备请求超时"
          : cause instanceof ConnectException ? "设备连接失败，请检查地址、端口及网络"
          : cause instanceof UnknownHostException ? "设备地址无法解析"
          : cause instanceof SocketException ? "设备连接中断"
          : "设备请求或响应读取失败";
      long duration = elapsed(started);
      log.warn("人员同步设备通信失败，方法={}，接口={}，异常类型={}，耗时={}ms",
          method, path, cause.getClass().getSimpleName(), duration);
      throw failure(reason + "；接口=" + path + "；耗时=" + duration + "ms；连接超时="
          + device.getConnectTimeoutMs() + "ms，读取超时=" + device.getReadTimeoutMs() + "ms", write);
    }
    Document document;
    try {
      if (response == null || response.length() > 4 * 1024 * 1024) throw new IllegalArgumentException();
      DocumentBuilderFactory parser = DocumentBuilderFactory.newInstance();
      parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      parser.setFeature("http://xml.org/sax/features/external-general-entities", false);
      parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      parser.setXIncludeAware(false);
      parser.setExpandEntityReferences(false);
      javax.xml.parsers.DocumentBuilder builder = parser.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      document = builder.parse(new InputSource(new StringReader(response)));
    } catch (Exception e) {
      throw failure("设备XML响应无效", write);
    }
    String code = value(document.getDocumentElement(), "statusCode").trim();
    if (!blank(code) && !"0".equals(code)) {
      // 非零业务码表示设备明确拒绝，沿用确定失败语义，不触发写入结果不明时的补偿。
      // 原始 message、requestURL 可能含凭据或人员资料；原因取本地定义，路径取本次固定接口。
      String safe = safeCode(code);
      throw failure(P6sStatusCodes.description(safe) + "；状态码 " + safe + "；接口=" + path, false);
    }
    if (write && (!"ResponseStatus".equals(document.getDocumentElement().getTagName()) || !"0".equals(code)))
      throw failure("设备未返回成功状态码", true);
    return document;
  }

  /**
   * 与浏览器 FormData 保持一致：文本部分只发送 Content-Disposition，文件部分保留 JPEG 类型。
   * Spring 默认会给每个部分增加 Content-Type/Content-Length，空 FaceUUID 也带独立长度；
   * 移除这些可选头，减少嵌入式固件表单解析差异。外层请求仍由 Spring 缓冲完整表单、
   * 计算总 Content-Length 并生成 boundary，不手工拼接二进制照片，也不改变任何业务字段。
   */
  private static void useBrowserMultipart(RestTemplate client) {
    // 必须在FormHttpMessageConverter完成序列化后修正外层头：它会重新生成boundary并覆盖Content-Type。
    // 现场固件对同一份表单实测：";boundary="返回415，"; boundary="才进入业务处理。
    // 拦截器只补参数分隔空格，不改变boundary或二进制请求体，也不重试可能已执行的写请求。
    client.getInterceptors().add((request, body, execution) -> {
      String type = request.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
      if (type != null && type.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE))
        request.getHeaders().set(HttpHeaders.CONTENT_TYPE, type.replace(";boundary=", "; boundary="));
      return execution.execute(request, body);
    });
    StringHttpMessageConverter text = new StringHttpMessageConverter(StandardCharsets.UTF_8) {
      @Override protected void addDefaultHeaders(HttpHeaders headers, String value, MediaType type) {
        // 普通 FormData 文本字段不声明独立的类型或长度，内容始终按 UTF-8 编码。
      }
    };
    ResourceHttpMessageConverter file = new ResourceHttpMessageConverter() {
      @Override protected void addDefaultHeaders(HttpHeaders headers, Resource value, MediaType type) {
        // JPEG Content-Type 已由 putPhoto 显式提供，文件部分无需额外 Content-Length。
      }
    };
    for (HttpMessageConverter<?> converter : client.getMessageConverters())
      if (converter instanceof FormHttpMessageConverter)
        ((FormHttpMessageConverter) converter).setPartConverters(Arrays.asList(text, file));
  }

  /** 使用单调时钟统计请求耗时，避免设备对时或系统校时使耗时出现负数。 */
  private static long elapsed(long started) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
  }

  private static String safeCode(String value) {
    // 现场设备的业务错误码可能为负数（如编解码失败返回 -2）；仅透传有限长度的整数，
    // 避免响应中夹带换行、人员资料等内容进入日志和前端错误提示。
    return value.matches("-?[0-9]{1,10}") ? value : "未知";
  }

  public static String xml(String root, Map<String, String> fields) {
    StringBuilder body = new StringBuilder("<" + root + ">");
    fields.forEach((key, value) -> body.append('<').append(key).append('>')
        .append(escape(value)).append("</").append(key).append('>'));
    return body.append("</").append(root).append('>').toString();
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
  }

  private static String value(Element element, String name) {
    NodeList nodes = element.getElementsByTagName(name);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
  }

  private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
  private static DeviceSyncException failure(String message, boolean uncertain) {
    return new DeviceSyncException(message, uncertain);
  }
}
