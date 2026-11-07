package com.moshanghuakai.exhibition.device;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** 本机隔离HTTP设备，验证真实XML/multipart传输；从不请求现场摄像头。 @author MoShangHuaKai */
public class MockFaceDevice implements AutoCloseable {
  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final AtomicInteger sequence = new AtomicInteger();
  public final Map<String, Map<String, String>> people = new ConcurrentHashMap<>();
  public final Map<String, byte[]> photos = new ConcurrentHashMap<>();
  public final List<String> writes = new CopyOnWriteArrayList<>();
  public volatile String rejectedName;
  public volatile String deleteStatusCode;
  // 按路径替换响应，复现厂商拒绝及异常 XML；路径为空时覆盖全部接口，供只读诊断验证。
  public volatile String responsePath, responseXml;
  // 可替换只读脸库响应，复现设备清空脸库、配置失效及响应数字身份异常。
  public volatile String faceGroupsXml;
  public volatile boolean rejectDeletes, acceptDeleteWithoutChange, loseCreateReply, duplicateCreation, rejectRestore;
  public volatile int createReplyDelayMs;
  public volatile boolean invalidXml, invalidAuthentication, badSafetyCode;
  public volatile boolean browserMultipartOnly;
  public volatile boolean dropCreateConnection;
  public volatile Runnable beforeWrite = () -> {};

  public MockFaceDevice() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(executor);
      server.createContext("/", this::handle);
      server.start();
    } catch (IOException e) { throw new IllegalStateException(e); }
  }

  public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  public void reset() {
    people.clear(); photos.clear(); writes.clear();
    rejectedName = null;
    deleteStatusCode = null;
    responsePath = responseXml = null;
    faceGroupsXml = null;
    rejectDeletes = acceptDeleteWithoutChange = loseCreateReply = duplicateCreation = rejectRestore = false;
    invalidXml = invalidAuthentication = badSafetyCode = false;
    browserMultipartOnly = false;
    dropCreateConnection = false;
    createReplyDelayMs = 0;
    beforeWrite = () -> {};
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      String auth = "Basic " + Base64.getEncoder().encodeToString("device-user:device-password".getBytes(StandardCharsets.UTF_8));
      if (invalidAuthentication || !auth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        respond(exchange, 401, "unauthorized"); return;
      }
      String path = exchange.getRequestURI().getPath();
      if (responseXml != null && (responsePath == null || responsePath.equals(path))) {
        read(exchange.getRequestBody());
        respond(exchange, 200, responseXml); return;
      }
      if (invalidXml) { respond(exchange, 200, "not xml"); return; }
      if ("/FaceGroups/QueryAll".equals(path)) {
        checkMethod(exchange, "PUT");
        respond(exchange, 200, faceGroupsXml == null
            ? "<FaceGroups><FaceGroup><GroupID>7</GroupID><GroupID2>staff-group</GroupID2></FaceGroup></FaceGroups>"
            : faceGroupsXml);
        return;
      }
      String type = exchange.getRequestHeaders().getFirst("Content-Type");
      // 复现现场固件的外层头解析限制：boundary前缺少空格会在处理照片前直接返回415。
      if (browserMultipartOnly && type != null && type.startsWith("multipart/form-data")
          && !type.startsWith("multipart/form-data; boundary=")) {
        respond(exchange, 415, "http upload failed"); return;
      }
      byte[] body = read(exchange.getRequestBody());
      Map<String, String> fields = new LinkedHashMap<>();
      byte[] image = null;
      if (type != null && type.startsWith("multipart/form-data")) {
        // 严格模式按浏览器表单验收实际传输字节，外层必须有总长度，不能分块发送。
        if (browserMultipartOnly && (exchange.getRequestHeaders().getFirst("Content-Length") == null
            || exchange.getRequestHeaders().getFirst("Transfer-Encoding") != null))
          throw new IllegalStateException("Upload requires a fixed-length request");
        String boundary = type.substring(type.indexOf("boundary=") + 9).replace("\"", "");
        String binary = new String(body, StandardCharsets.ISO_8859_1);
        for (String part : binary.split(Pattern.quote("--" + boundary))) {
          int divider = part.indexOf("\r\n\r\n");
          if (divider < 0) continue;
          Matcher name = Pattern.compile("name=\"([^\"]+)\"").matcher(part.substring(0, divider));
          if (!name.find()) continue;
          String partHeaders = part.substring(0, divider).toLowerCase(Locale.ROOT);
          if (browserMultipartOnly && (partHeaders.contains("content-length:")
              || !"file".equals(name.group(1)) && partHeaders.contains("content-type:")))
            throw new IllegalStateException("Unexpected multipart part headers");
          String data = part.substring(divider + 4);
          if (data.endsWith("\r\n")) data = data.substring(0, data.length() - 2);
          byte[] bytes = data.getBytes(StandardCharsets.ISO_8859_1);
          if ("file".equals(name.group(1))) image = bytes;
          else fields.put(name.group(1), new String(bytes, StandardCharsets.UTF_8));
        }
      } else {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(body));
        NodeList elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
          Element element = (Element) elements.item(i);
          if (element.getChildNodes().getLength() <= 1) fields.put(element.getTagName(), element.getTextContent());
        }
      }
      if ("/FaceGroup/QueryPersonInfoList".equals(path)) {
        checkMethod(exchange, "PUT");
        if (!"7".equals(fields.get("GroupID"))) throw new IllegalStateException("Wrong group");
        List<String> ids = new ArrayList<>(people.keySet());
        Collections.sort(ids);
        int from = (Integer.parseInt(fields.get("Pagenum")) - 1) * 100;
        StringBuilder xml = new StringBuilder("<PersonInfoList>");
        for (int i = from; i < Math.min(from + 100, ids.size()); i++) {
          String uuid = ids.get(i);
          Map<String, String> person = people.get(uuid);
          xml.append("<PersonInfo><FaceUUID>").append(escape(person.getOrDefault("FaceUUID", uuid)))
              .append("</FaceUUID>");
          person.forEach((key, value) -> {
            if (!"FaceUUID".equals(key)) xml.append('<').append(key).append('>')
                .append(escape(value)).append("</").append(key).append('>');
          });
          xml.append("</PersonInfo>");
        }
        respond(exchange, 200, xml.append("</PersonInfoList>").toString()); return;
      }
      checkMethod(exchange, path.endsWith("UpdatePersonInfoAndFaceImage") ? "POST" : "PUT");
      String md5 = hex(MessageDigest.getInstance("MD5").digest(
          ("owner-original" + fields.get("SystemTime")).getBytes(StandardCharsets.UTF_8)));
      if (badSafetyCode || !new StringBuilder(md5.substring(0, 8)).reverse().toString().equals(fields.get("Ownner"))
          || !"staff-group".equals(fields.get("FaceGroupID"))) {
        // 厂商 4208 表示安全码不匹配，4101 表示人脸算法未初始化。
        respond(exchange, 200, status("4208")); return;
      }
      beforeWrite.run();
      String uuid = fields.get("FaceUUID");
      // 复现真机返回 HTTP 200、负数业务码的情况，确认客户端能保留诊断信息。
      if (deleteStatusCode != null && path.endsWith("DeletePersonList")) {
        respond(exchange, 200, status(deleteStatusCode)); return;
      }
      // 真机对缺少数字 GroupID 或 PersonID 的删除请求返回 -2，不能把 HTTP 200 当作成功。
      if (path.endsWith("DeletePersonList")
          && (!"7".equals(fields.get("GroupID")) || !"0".equals(fields.get("PersonID")))) {
        respond(exchange, 200, status("-2")); return;
      }
      if (Objects.equals(rejectedName, fields.get("Name")) && rejectedName != null
          || rejectDeletes && path.endsWith("DeletePersonList")
          || rejectRestore && !uuid.isEmpty() && !people.containsKey(uuid)) {
        respond(exchange, 200, status("4112")); return;
      }
      if (path.endsWith("DeletePersonList")) {
        // 模拟固件返回业务成功但人员仍在脸库中，验证本地删除不会误提交。
        if (acceptDeleteWithoutChange) { respond(exchange, 200, status("0")); return; }
        people.remove(uuid); photos.remove(uuid); writes.add("DELETE:" + uuid);
      } else if (path.endsWith("UpdatePersonInfoAndFaceImage")) {
        if (image == null || image.length < 2 || image[0] != (byte) 0xff || image[1] != (byte) 0xd8)
          throw new IllegalStateException("Missing JPEG photo");
        // 当前后台为新增人员显式分配 UUID；设备按该身份是否已经存在区分新增和照片更新。
        boolean create = uuid.isEmpty() || !people.containsKey(uuid);
        if (uuid.isEmpty()) uuid = "device-" + sequence.incrementAndGet();
        Map<String, String> person = people.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        person.put("Name", fields.get("Name")); person.put("Sex", fields.get("Sex"));
        person.putIfAbsent("CertificateType", "none");
        photos.put(uuid, image);
        writes.add((create ? "CREATE:" : "PHOTO:") + uuid);
        // 已执行新增后直接断开响应，覆盖真实 SocketException 下不会重复新增的约束。
        if (create && dropCreateConnection) {
          dropCreateConnection = false; exchange.close(); return;
        }
        if (create && duplicateCreation) {
          String another = "device-" + sequence.incrementAndGet();
          people.put(another, new ConcurrentHashMap<>(person)); photos.put(another, image);
        }
        if (create && loseCreateReply) { loseCreateReply = false; respond(exchange, 500, "lost reply"); return; }
        if (create && createReplyDelayMs > 0) Thread.sleep(createReplyDelayMs);
      } else if (path.endsWith("ModifyPersonInfo")) {
        if (!people.containsKey(uuid)) { respond(exchange, 200, status("4204")); return; }
        for (String key : Arrays.asList("Name", "Sex", "CertificateType", "CertificateID", "Country", "Address", "Birthday"))
          people.get(uuid).put(key, fields.getOrDefault(key, ""));
        writes.add("MODIFY:" + uuid);
      } else { respond(exchange, 404, "unknown path"); return; }
      respond(exchange, 200, status("0"));
    } catch (Exception e) {
      try { respond(exchange, 500, "mock protocol validation failed"); }
      catch (IOException ignored) { exchange.close(); }
    }
  }

  private static String status(String code) { return "<ResponseStatus><statusCode>" + code + "</statusCode></ResponseStatus>"; }
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte b : bytes) text.append(String.format("%02x", b & 0xff));
    return text.toString();
  }
  private static byte[] read(InputStream input) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192]; int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    return output.toByteArray();
  }
  private static void checkMethod(HttpExchange exchange, String method) {
    if (!method.equals(exchange.getRequestMethod())) throw new IllegalStateException("Wrong HTTP method");
  }
  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    // 故意不声明charset，确认客户端按设备文档使用UTF-8解码中文。
    exchange.getResponseHeaders().set("Content-Type", "text/plain");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
  }
  @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
