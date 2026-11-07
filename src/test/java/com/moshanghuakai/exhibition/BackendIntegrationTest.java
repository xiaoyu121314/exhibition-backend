package com.moshanghuakai.exhibition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.*;
import com.moshanghuakai.exhibition.device.*;
import com.moshanghuakai.exhibition.device.MockFaceDevice;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.*;
import com.moshanghuakai.exhibition.staff.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 默认使用 H2；test.datasource.config 可指定隔离 MySQL 5.7 的数据源 YAML，不读取环境变量。 @author MoShangHuaKai */
@SpringBootTest
@AutoConfigureMockMvc
class BackendIntegrationTest {
  private static final MockFaceDevice faceDevice = new MockFaceDevice();

  @AfterAll
  static void closeFaceDevice() { faceDevice.close(); }
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    String datasourceFile = System.getProperty("test.datasource.config");
    Properties datasource = new Properties();
    if (datasourceFile != null) {
      org.springframework.beans.factory.config.YamlPropertiesFactoryBean loader =
          new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
      loader.setResources(new org.springframework.core.io.FileSystemResource(datasourceFile));
      datasource = Objects.requireNonNull(loader.getObject());
    }
    String configured = datasource.getProperty("spring.datasource.url");
    String username = datasource.getProperty("spring.datasource.username", "root");
    String password = datasource.getProperty("spring.datasource.password", "");
    boolean mysql = configured != null && !configured.isEmpty();
    if (mysql
        && !configured.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/exhibition_test(?:\\?.*)?")) {
      throw new IllegalArgumentException("集成测试仅允许本机隔离 exhibition_test 库，禁止连接业务数据库");
    }
    registry.add(
        "spring.datasource.url",
        () ->
            mysql
                ? configured
                : "jdbc:h2:mem:exhibition;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    registry.add("spring.datasource.username", () -> mysql ? username : "sa");
    registry.add("spring.datasource.password", () -> mysql ? password : "");
    registry.add(
        "spring.datasource.driver-class-name",
        () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
    String sql =
        new String(Files.readAllBytes(Paths.get("sql/schema.sql")), StandardCharsets.UTF_8);
    if (!mysql)
      sql = sql.replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci", "");
    Files.createDirectories(Paths.get("target"));
    Files.write(Paths.get("target/test-schema.sql"), sql.getBytes(StandardCharsets.UTF_8));
    registry.add("spring.sql.init.mode", () -> "always");
    registry.add("spring.sql.init.schema-locations", () -> "file:./target/test-schema.sql");
    registry.add("exhibition.storage-directory", () -> "./target/test-data");
    registry.add("exhibition.jobs-enabled", () -> "false");
    // 生产配置已启用识别；测试逐例构造契约和身份，不在测试上下文启动时提前开启统计。
    registry.add("exhibition.recognition.enabled", () -> "false");
    registry.add("exhibition.face-feature.enabled", () -> "false");
    registry.add("exhibition.devices[0].serial-number", () -> "CAM-TEST");
    registry.add("exhibition.devices[0].base-url", faceDevice::url);
    registry.add("exhibition.devices[0].username", () -> "device-user");
    registry.add("exhibition.devices[0].password", () -> "device-password");
    registry.add("exhibition.devices[0].face-group-id", () -> "staff-group");
    registry.add("exhibition.devices[0].owner-identifier", () -> "owner-original");
  }

  @Autowired MockMvc mvc;
  @Autowired org.springframework.core.env.Environment environment;
  @Autowired org.springframework.context.ApplicationContext applicationContext;
  @SpyBean StorageService storage;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @SpyBean StaffService staff;
  @SpyBean StaffMapper staffMapper;
  @Autowired StaffExcelService excel;
  @Autowired ImportMapper imports;
  @Autowired EventMapper events;
  @Autowired DeviceEventService devices;
  @Autowired DeviceFaceClient faceClient;
  @Autowired ExhibitionProperties properties;
  @Autowired RecognitionService recognition;
  @Autowired FaceFeatureService faceFeatures;
  @SpyBean FaceFeatureMapper faceFeatureMapper;
  @org.springframework.boot.test.mock.mockito.MockBean FaceFeatureClient faceFeatureClient;
  @Autowired IdentityMapper identities;
  @Autowired MediaMapper mediaMapper;

  @BeforeEach
  void clean() {
    faceDevice.reset();
    properties.setRecognition(new ExhibitionProperties.Recognition());
    properties.setFaceFeature(new ExhibitionProperties.FaceFeature());
    properties.getDevices().get(0).setReadTimeoutMs(15000);
    properties.getDevices().get(0).setBaseUrl(faceDevice.url());
    properties.getDevices().get(0).setPassword("device-password");
    properties.getDevices().get(0).setOwnerIdentifier("owner-original");
    for (String table :
        Arrays.asList(
            "exh_face_feature_event",
            "exh_person_daily",
            "exh_device_identity",
            "exh_staff_device",
            "exh_capture_event",
            "exh_staff_import_task",
            "exh_staff",
            "exh_device",
            "exh_media")) db.update("DELETE FROM " + table);
  }

  @Test
  void splitYamlProfilesLoadBusinessConfiguration() {
    assertThat(environment.getActiveProfiles()).contains("datasource", "exhibition", "swagger");
    assertThat(environment.getProperty("exhibition.face-model-directory")).isNull();
    // 验证实时视频引用设备凭据时使用运行配置的值，打包模板无需再保存一份明文账号密码。
    assertThat(properties.getLive().getRtspUrl())
        .isEqualTo("rtsp://device-user:device-password@192.168.0.232:554/live/0/MAIN");
  }

  /** 将运行时映射与实际生成的文档逐一比较，新增接口漏收录或漏填说明时直接失败。 */
  @Test
  void swaggerDocumentsEveryBusinessOperationAndItsAuthentication() throws Exception {
    JsonNode document = apiDocument();
    JsonNode paths = document.path("paths");
    Set<String> documented = new TreeSet<>();
    paths
        .fields()
        .forEachRemaining(
            path ->
                path.getValue()
                    .fields()
                    .forEachRemaining(
                        operation -> {
                          if (Arrays.asList(
                                  "get", "post", "put", "delete", "patch", "head", "options")
                              .contains(operation.getKey())) {
                            documented.add(
                                operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
                            assertThat(operation.getValue().path("summary").asText()).isNotBlank();
                            assertThat(operation.getValue().path("tags").size()).isGreaterThan(0);
                            // 所有业务接口均直接访问，文档不再声明访问密钥或 401 响应。
                            assertThat(operation.getValue().path("security").size()).isZero();
                            assertThat(operation.getValue().path("responses").has("401")).isFalse();
                          }
                        }));
    Set<String> mapped = new TreeSet<>();
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mapping =
        applicationContext.getBean(
            "requestMappingHandlerMapping",
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
                .class);
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              if (handler.getBeanType().getName().startsWith("com.moshanghuakai.exhibition.")) {
                info.getPatternValues()
                    .forEach(
                        path ->
                            info.getMethodsCondition()
                                .getMethods()
                                .forEach(method -> mapped.add(method.name() + " " + path)));
              }
            });
    // 单张照片删除接口已移除；同时校验运行时映射与文档，避免仍然暴露旧入口。
    assertThat(documented).hasSize(26).isEqualTo(mapped);
    assertThat(paths.has("/api/admin/recognition/face-features/retry")).isTrue();
    assertThat(paths.has("/api/admin/staff/{id}/face-images/{imageId}")).isFalse();
    assertThat(paths.has("/api/admin/staff/{id}/face-images")).isFalse();
    assertThat(paths.has("/api/admin/captures/{id}/retry")).isFalse();
    assertThat(applicationContext.containsBean("faceClient")).isFalse();
    assertThat(applicationContext.containsBean("recognitionWorker")).isFalse();
    // 工作人员沿用设备结果；新增本机特征服务仅负责人员日去重，不恢复旧的模型接口。
    assertThat(applicationContext.containsBean("recognitionService")).isTrue();
    assertThat(applicationContext.containsBean("faceFeatureService")).isTrue();
    assertThat(document.path("components").path("securitySchemes").size()).isZero();
    // 工号为后台生成的返回字段，不再作为新增或修改参数暴露。
    assertThat(document.at("/components/schemas/StaffRequest/properties").has("staffNo")).isFalse();
    // 文档不应带出设备管理凭据或识别特征。
    assertThat(document.toString())
        .doesNotContain(
            "device-password",
            "owner-original",
            "\"embedding\"");
    Files.write(
        Paths.get("target/swagger-openapi.json"),
        json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
  }

  /** 校验页面、初始化脚本与文档均可直接读取。 */
  @Test
  void swaggerUiAndDocumentationResourcesAreAccessible() throws Exception {
    mvc.perform(get("/swagger-ui.html"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/swagger-ui/index.html"));
    mvc.perform(get("/swagger-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    mvc.perform(get("/swagger-ui/swagger-initializer.js"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/v3/api-docs")));
    mvc.perform(get("/swagger-ui/swagger-ui.css")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs/swagger-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    mvc.perform(get("/v3/api-docs.yaml"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi:")));
  }

  /** 文件上传、二进制下载和设备原始报文不能被自动推断为普通 JSON 参数。 */
  @Test
  void swaggerDescribesFilesAndRawCameraBodyWithoutAuthenticationHeaders() throws Exception {
    JsonNode document = apiDocument();
    JsonNode paths = document.path("paths");
    for (String path :
        Arrays.asList("/api/admin/staff/import-template", "/api/admin/staff/export")) {
      String method = path.endsWith("export") ? "post" : "get";
      JsonNode content =
          paths.path(path).path(method).path("responses").path("200").path("content");
      assertThat(
              content
                  .path("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                  .path("schema")
                  .path("format")
                  .asText())
          .isEqualTo("binary");
    }
    for (String path :
        Arrays.asList("/api/admin/staff/import", "/api/admin/staff/face-images")) {
      JsonNode body =
          paths
              .path(path)
              .path("post")
              .path("requestBody")
              .path("content")
              .path("multipart/form-data")
              .path("schema");
      JsonNode schema = dereference(document, body);
      assertThat(schema.path("properties").path("file").path("format").asText())
          .isEqualTo("binary");
    }
    JsonNode camera = paths.path("/api/device/p6s/events").path("post");
    assertThat(camera.path("parameters").size()).isZero();
    assertThat(camera.path("requestBody").path("required").asBoolean()).isTrue();
    assertThat(
            camera
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("examples")
                .size())
        .isEqualTo(3);
    assertThat(camera.path("responses").has("503")).isTrue();
    assertThat(document.at("/components/schemas/ImportTask/properties/errors/type").asText())
        .isEqualTo("array");
    assertThat(paths.path("/api/media/{id}").path("get").path("parameters").size()).isEqualTo(1);
  }

  private JsonNode apiDocument() throws Exception {
    return json.readTree(
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private JsonNode dereference(JsonNode document, JsonNode schema) {
    return schema.has("$ref") ? document.at(schema.get("$ref").asText().substring(1)) : schema;
  }


  private static byte[] photo() throws Exception {
    BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "jpg", bytes);
    return bytes.toByteArray();
  }

  private StaffRequest request() {
    StaffRequest request = new StaffRequest();
    request.setName("测试人员");
    request.setSex("male");
    request.setPosition("讲解员");
    try { request.setImageId(staff.upload(photo()).getId()); }
    catch (Exception e) { throw new IllegalStateException(e); }
    return request;
  }

  private byte[] workbook(String no, boolean withPhoto) throws Exception {
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(excel.template()));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.getSheet("工作人员");
      Row row = sheet.getRow(1);
      String[] values = {no, "工作人员甲", "讲解员", "展厅", "启用", "male"};
      for (int c = 0; c < values.length; c++) row.getCell(c).setCellValue(values[c]);
      if (withPhoto) {
        int picture = book.addPicture(photo(), Workbook.PICTURE_TYPE_JPEG);
        sheet
            .createDrawingPatriarch()
            .createPicture(new XSSFClientAnchor(0, 0, 0, 0, 6, 1, 7, 2), picture);
      }
      book.write(output);
      return output.toByteArray();
    }
  }

  /** 验证列表返回媒体表真实路径，包含已保存的 URL 前缀时也原样返回，缺失头像不丢失人员。 */
  @Test
  void staffListReturnsStoredAvatarPathAndKeepsStaffWithoutMedia() throws Exception {
    Media avatar = storage.saveImage(photo(), "STAFF");
    Staff person = new Staff();
    person.setId(Ids.next()); person.setStaffNo("001"); person.setName("头像路径测试");
    person.setSex("male"); person.setPosition("讲解员"); person.setDepartment("展厅");
    person.setEnabled(false); person.setAvatarId(avatar.getId());
    staffMapper.insert(person);
    mvc.perform(get("/api/admin/staff").param("staffNo", "001").param("department", "展厅")
            .param("enabled", "false"))
        .andExpect(status().isOk()).andExpect(jsonPath("data.total").value(1))
        .andExpect(jsonPath("data.records[0].avatarId").value(avatar.getId()))
        .andExpect(jsonPath("data.records[0].avatarUrl").value(avatar.getRelativePath()));

    // 地址完全以数据库为准，不能通过当前日期或图片 ID 重新拼接，也不重复添加 URL 前缀。
    String storedPath = "/profile/upload/staff/2026/11/04/avatar.jpg";
    db.update("UPDATE exh_media SET relative_path=? WHERE id=?", storedPath, avatar.getId());
    mvc.perform(get("/api/admin/staff")).andExpect(status().isOk())
        .andExpect(jsonPath("data.records[0].avatarUrl").value(storedPath));

    db.update("UPDATE exh_staff SET avatar_id=? WHERE id=?", Ids.next(), person.getId());
    mvc.perform(get("/api/admin/staff")).andExpect(status().isOk())
        .andExpect(jsonPath("data.total").value(1))
        .andExpect(jsonPath("data.records[0].avatarUrl").value(org.hamcrest.Matchers.nullValue()));
    db.update("UPDATE exh_staff SET avatar_id=NULL WHERE id=?", person.getId());
    mvc.perform(get("/api/admin/staff")).andExpect(status().isOk())
        .andExpect(jsonPath("data.total").value(1))
        .andExpect(jsonPath("data.records[0].avatarUrl").value(org.hamcrest.Matchers.nullValue()));
    mvc.perform(get("/api/admin/staff").param("page", "2").param("size", "1"))
        .andExpect(status().isOk()).andExpect(jsonPath("data.total").value(1))
        .andExpect(jsonPath("data.records").isEmpty());
  }

  @Test
  void templateDownloadWithoutToken() throws Exception {
    byte[] bytes =
        mvc.perform(
                get("/api/admin/staff/import-template"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(book.getNumberOfSheets()).isEqualTo(2);
      assertThat(book.getSheet("工作人员").getRow(0).getCell(0).getStringCellValue()).isEqualTo("工号");
    }
  }

  @Test
  void crudGeneratesNumbersPreservesThemOnUpdateAndNeverReusesDeletedNumbers() {
    Staff created = staff.create(request());
    assertThat(created.getStaffNo()).isEqualTo("001");
    StaffRequest update = request();
    update.setVersion(created.getVersion());
    update.setName("修改姓名");
    Staff changed = staff.update(created.getId(), update);
    assertThat(changed.getName()).isEqualTo("修改姓名");
    assertThat(changed.getStaffNo()).isEqualTo(created.getStaffNo());
    assertThatThrownBy(() -> staff.update(created.getId(), update))
        .isInstanceOf(BusinessException.class);
    staff.delete(changed.getId());
    assertThatThrownBy(() -> staff.require(changed.getId())).isInstanceOf(BusinessException.class);
    assertThat(staff.create(request()).getStaffNo()).isEqualTo("002");
  }

  /** 跨越三位边界后仍按数值取最大值，避免把999错误地排在1000之后。 */
  @Test
  void staffNumbersGrowPastThreeDigitsAndRejectOverflow() {
    StaffRequest disabled = request(); disabled.setEnabled(false);
    Staff first = staff.create(disabled);
    db.update("UPDATE exh_staff SET staff_no='999' WHERE id=?", first.getId());
    assertThat(staff.create(request()).getStaffNo()).isEqualTo("1000");
    assertThat(staff.create(request()).getStaffNo()).isEqualTo("1001");
    db.update("UPDATE exh_staff SET staff_no=? WHERE id=?", String.valueOf(Long.MAX_VALUE), first.getId());
    assertThatThrownBy(() -> staff.create(disabled)).hasMessageContaining("工号已达到上限");
    assertThat(staffMapper.count(null, null, null, null)).isEqualTo(3);
  }

  /** 并发提交新增请求必须串行分配工号，不能都读取到同一个最大值。 */
  @Test
  void concurrentCreatesReceiveDistinctConsecutiveStaffNumbers() throws Exception {
    List<StaffRequest> requests = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      StaffRequest create = request(); create.setEnabled(false); requests.add(create);
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Staff>> results = new ArrayList<>();
      for (StaffRequest create : requests) results.add(pool.submit(() -> {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("新增测试超时");
        return staff.create(create);
      }));
      start.countDown();
      List<String> numbers = new ArrayList<>();
      for (Future<Staff> result : results) numbers.add(result.get(10, TimeUnit.SECONDS).getStaffNo());
      assertThat(numbers).containsExactlyInAnyOrder("001", "002", "003", "004");
    } finally { start.countDown(); pool.shutdownNow(); }
  }

  /** 空工号可省略单元格或使用空白类型；批量分配与后续单条新增衔接。 */
  @Test
  void excelAssignsConsecutiveNumbersToMultipleBlankCells() throws Exception {
    byte[] source;
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(twoPersonWorkbook()));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.getSheet("工作人员");
      sheet.getRow(1).removeCell(sheet.getRow(1).getCell(0));
      sheet.getRow(2).getCell(0).setBlank();
      book.write(output); source = output.toByteArray();
    }
    ImportTask task = excel.submit(source); excel.process(task);
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("SUCCESS");
    assertThat(imports.find(task.getId()).getCreatedCount()).isEqualTo(2);
    assertThat(staffMapper.findByNo("001").getName()).isEqualTo("工作人员甲");
    assertThat(staffMapper.findByNo("002").getName()).isEqualTo("工作人员乙");
    assertThat(staff.create(request()).getStaffNo()).isEqualTo("003");
  }

  /** 非空工号只能更新已有人员；未知工号和已删除工号必须在设备动作前报错。 */
  @Test
  void excelRejectsUnknownAndDeletedStaffNumbersBeforeDeviceWrites() throws Exception {
    ImportTask unknown = excel.submit(workbook("999", true)); excel.process(unknown);
    assertThat(imports.find(unknown.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(imports.find(unknown.getId()).getErrors()).contains("工号不存在");
    assertThat(faceDevice.writes).isEmpty();
    Staff saved = staff.create(request()); staff.delete(saved.getId());
    int writes = faceDevice.writes.size();
    ImportTask deleted = excel.submit(workbook(saved.getStaffNo(), true)); excel.process(deleted);
    assertThat(imports.find(deleted.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(imports.find(deleted.getId()).getErrors()).contains("工号已删除");
    assertThat(faceDevice.writes).hasSize(writes);
  }

  @Test
  void embeddedPhotoImportExportAndReimport() throws Exception {
    ImportTask task = excel.submit(workbook("", true));
    excel.process(task);
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("SUCCESS");
    Staff imported = staffMapper.findByNo("001");
    assertThat(imported.getAvatarId()).isNotNull();
    byte[] output = excel.export(new StaffExcelService.Filter());
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(output))) {
      assertThat(book.getSheet("工作人员").getRow(1).getCell(0).getStringCellValue())
          .isEqualTo("001");
      assertThat(book.getAllPictures()).hasSize(1);
    }
    ImportTask again = excel.submit(output);
    excel.process(again);
    assertThat(imports.find(again.getId()).getStatus()).isEqualTo("SUCCESS");
    assertThat(staffMapper.count(null, null, null, null)).isEqualTo(1);
  }

  @Test
  void updateWithoutImageKeepsAvatarAndFailureIsAtomic() throws Exception {
    ImportTask initial = excel.submit(workbook("", true));
    excel.process(initial);
    Staff original = staffMapper.findByNo("001");
    ImportTask update = excel.submit(workbook(original.getStaffNo(), false));
    excel.process(update);
    assertThat(imports.find(update.getId()).getStatus()).isEqualTo("SUCCESS");
    assertThat(staffMapper.findByNo(original.getStaffNo()).getAvatarId()).isEqualTo(original.getAvatarId());
    byte[] invalid;
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook(original.getStaffNo(), false)));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      book.getSheet("工作人员").getRow(1).getCell(1).setCellValue("不得提交的姓名");
      Row row = book.getSheet("工作人员").createRow(2);
      String[] values = {"", "无头像人员", "岗位", "", "启用", "male"};
      for (int c = 0; c < values.length; c++)
        row.createCell(c, CellType.STRING).setCellValue(values[c]);
      book.write(out);
      invalid = out.toByteArray();
    }
    ImportTask failed = excel.submit(invalid);
    excel.process(failed);
    assertThat(imports.find(failed.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(staffMapper.findByNo(original.getStaffNo()).getName()).isEqualTo("工作人员甲");
    assertThat(staffMapper.count(null, null, null, null)).isEqualTo(1);
  }


  @Test
  void deviceCallbackWithoutTokenOrSignatureAcceptsRepeatedHeartbeat() throws Exception {
    String body =
        "{\"operator\":\"heartbeat\",\"deviceInfo\":{\"serialNumber\":\"CAM-TEST\"},\"info\":{\"eventId\":1,\"time\":\""
            + OffsetDateTime.now()
            + "\"}}";
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
        post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(body);
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        // 摄像头成功响应应为有明确字节长度的原始 JSON，不能依赖 chunked 分块或业务包装。
        .andExpect(header().doesNotExist("Transfer-Encoding"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getHeader("Content-Length"))
                    .isEqualTo(
                        String.valueOf(result.getResponse().getContentAsByteArray().length)))
        .andExpect(jsonPath("operator").value("heartbeat-Ack"))
        .andExpect(jsonPath("info.eventId").value(1))
        .andExpect(jsonPath("info.eventSendMode").value("realTime"))
        .andExpect(
            result -> {
              // 校验官方示例的秒级时间形式，并确保回传的是当前北京时间，而不是请求中的旧时间。
              String ackTime =
                  json.readTree(result.getResponse().getContentAsByteArray())
                      .path("info")
                      .path("time")
                      .asText();
              assertThat(ackTime).matches("\\d{8}T\\d{6}\\+08");
              assertThat(
                      Duration.between(
                              DeviceEventService.parseTime(ackTime),
                              LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                          .abs()
                          .getSeconds())
                  .isLessThan(5);
            })
        .andExpect(jsonPath("result.errorNo").value(0))
        .andExpect(jsonPath("info.strategy.isSnapshotEnable").value(true));
    mvc.perform(request).andExpect(status().isOk());
    // 无需凭据不等于忽略业务配置：缺失或未知序列号仍需给出明确参数错误。
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("CAM-TEST", "UNKNOWN")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("message").value("设备序列号未配置"));
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("CAM-TEST", "")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("message").value("设备序列号不能为空"));
  }

  @Test
  void configurationAndScreenAccess() throws Exception {
    mvc.perform(
            get("/api/screen/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.hallName").value("一楼展厅"));
    mvc.perform(
            get("/api/screen/overview"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.totalCount").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("data.staffCount").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("data.guestCount").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("data.recognitionStatus").value("DISABLED"))
        .andExpect(jsonPath("data.countMode").value("NONE"));
  }

  @Test
  void transactionFailureRollsBackRowsAndNewFiles() throws Exception {
    byte[] source;
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook("", true)));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Row row = book.getSheet("工作人员").createRow(2);
      String[] values = {"", "工作人员乙", "讲解员", "展厅", "启用", "female"};
      for (int c = 0; c < values.length; c++)
        row.createCell(c, CellType.STRING).setCellValue(values[c]);
      int picture = book.addPicture(photo(), Workbook.PICTURE_TYPE_JPEG);
      book.getSheet("工作人员")
          .createDrawingPatriarch()
          .createPicture(new XSSFClientAnchor(0, 0, 0, 0, 6, 2, 7, 3), picture);
      book.write(out);
      source = out.toByteArray();
    }
    doThrow(new BusinessException("模拟第二行持久化失败"))
        .when(staffMapper).insert(argThat(person -> "工作人员乙".equals(person.getName())));
    long filesBefore = countStaffFiles();
    ImportTask task = excel.submit(source);
    excel.process(task);
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM exh_media WHERE owner_type='STAFF'", Integer.class))
        .isZero();
    assertThat(countStaffFiles()).isEqualTo(filesBefore);
    assertThat(faceDevice.people).isEmpty();
  }

  private long countStaffFiles() throws IOException {
    Path directory = Paths.get("target/test-data/staff");
    if (!Files.exists(directory)) return 0;
    try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }

  @Test
  void configurationReloadIsAtomicAndRetainsLastValidValue(
      @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
    Path file = directory.resolve("display.yml");
    Files.write(
        file,
        "exhibition:\n  name: 初始展厅\n  welcome-message: 初始欢迎语\n".getBytes(StandardCharsets.UTF_8));
    ExhibitionProperties local = new ExhibitionProperties();
    local.setDisplayFile(file.toString());
    DisplayConfiguration configuration = new DisplayConfiguration(local);
    configuration.initialize();
    Files.write(
        file,
        "exhibition:\n  name: 新展厅\n  welcome-message: 新欢迎语\n".getBytes(StandardCharsets.UTF_8));
    configuration.reload();
    assertThat(configuration.get().get("hallName")).isEqualTo("新展厅");
    Files.write(file, "exhibition: [invalid".getBytes(StandardCharsets.UTF_8));
    configuration.reload();
    assertThat(configuration.get().get("welcomeMessage")).isEqualTo("新欢迎语");
  }

  @Test
  void repeatedDeviceEventIsIdempotentButReusedIdAtNewTimeIsAccepted() throws Exception {
    String value =
        "{\"operator\":\"FaceSnapshot\",\"deviceInfo\":{\"serialNumber\":\"CAM-TEST\"},\"info\":{\"eventId\":1,\"time\":\""
            + LocalDateTime.now().withNano(0).minusSeconds(4)
            + "\",\"trackId\":1,\"CaptureImage\":{\"picture\":\""
            + Base64.getEncoder().encodeToString(photo())
            + "\"}}}";
    mvc.perform(
            post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(value))
        .andExpect(status().isOk());
    JsonNode event = json.readTree(value);
    devices.receive(properties.getDevices().get(0), event);
    devices.receive(properties.getDevices().get(0), event);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class))
        .isEqualTo(1);
    ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("info"))
        .put("time", LocalDateTime.now().withNano(0).minusSeconds(1).toString());
    devices.receive(properties.getDevices().get(0), event);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class))
        .isEqualTo(2);
    // 抓拍接收后已结束，不留下无人消费的识别任务，也不生成工作人员或嘉宾人数。
    assertThat(db.queryForList("SELECT status FROM exh_capture_event", String.class))
        .containsOnly("RECEIVED");
    mvc.perform(get("/api/admin/captures").param("status", "RECEIVED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("data.length()").value(2))
        .andExpect(jsonPath("data[0].personId").doesNotExist())
        .andExpect(jsonPath("data[0].score").doesNotExist());
  }

  @Test
  void invalidCalendarTimeIsNotSilentlyAssignedToAnotherDay() {
    assertThatThrownBy(() -> DeviceEventService.parseTime("20260230T101001+08"))
        .isInstanceOf(BusinessException.class);
    assertThat(DeviceEventService.parseTime("20261028T101001+08"))
        .isEqualTo(LocalDateTime.of(2026, 10, 28, 10, 10, 1));
  }


  @Test
  void interfacesAreAccessibleWithoutTokenAndNoUserAccountsAreCreated() throws Exception {
    assertThat(
            applicationContext.getBeansOfType(
                org.springframework.security.core.userdetails.UserDetailsService.class))
        .isEmpty();
    assertThat(environment.containsProperty("exhibition.admin-user")).isFalse();
    assertThat(environment.containsProperty("exhibition.admin-password")).isFalse();
    assertThat(environment.containsProperty("exhibition.admin-token")).isFalse();
    assertThat(environment.containsProperty("exhibition.screen-token")).isFalse();
    mvc.perform(get("/api/admin/staff"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("WWW-Authenticate"));
    mvc.perform(
            get("/api/admin/staff")
                .header(
                    "Authorization",
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(
                                "admin:irrelevant-password".getBytes(StandardCharsets.UTF_8))))
        .andExpect(status().isOk());
    // 访问凭据已不参与鉴权，客户端误传旧请求头也不会再触发 401。
    mvc.perform(get("/api/admin/staff").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isOk());
  }

  @Test
  void imagesAreAccessibleWithoutTokenAndImportFilesRemainUnsupported() throws Exception {
    Staff employee = staff.create(request());
    Media portrait = storage.require(employee.getAvatarId());
    Media capture = storage.saveImage(photo(), "CAPTURE");
    Media unbound = storage.saveImage(photo(), "UPLOAD");
    Media originalImport = storage.saveWorkbook(workbook("", true));
    mvc.perform(
            get("/api/media/" + portrait.getId()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(
            get("/api/media/" + capture.getId()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/media/" + unbound.getId()))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/media/" + originalImport.getId()))
        .andExpect(status().isForbidden());
  }

  @Test
  void recoveredImportRestartsValidationProgress() throws Exception {
    ImportTask task = excel.submit(workbook("", true));
    task.setStatus("PROCESSING");
    task.setPhase("VALIDATING");
    task.setProcessedRows(7);
    imports.update(task);
    imports.recover();
    ImportTask recovered = imports.find(task.getId());
    excel.process(recovered);
    ImportTask completed = imports.find(task.getId());
    assertThat(completed.getStatus()).isEqualTo("SUCCESS");
    assertThat(completed.getTotalRows()).isEqualTo(1);
    assertThat(completed.getProcessedRows()).isEqualTo(1);
  }

  /** 真实multipart入口只保存图片；人员及特征写入由后续JSON新增负责。 */
  @Test
  void independentPhotoUploadAndJsonEnrollment() throws Exception {
    byte[] body = mvc.perform(multipart("/api/admin/staff/face-images")
            .file(new org.springframework.mock.web.MockMultipartFile("file", "portrait.png", "image/png", photo())))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    String imageId = json.readTree(body).path("data").path("imageId").asText();
    assertThat(imageId).isNotBlank();
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(faceDevice.writes).isEmpty();
    StaffRequest request = request();
    request.setImageId(imageId);
    mvc.perform(post("/api/admin/staff").contentType(MediaType.APPLICATION_JSON)
             .content(json.writeValueAsBytes(request)))
        .andExpect(status().isOk()).andExpect(jsonPath("data.avatarId").value(imageId))
        .andExpect(jsonPath("data.sex").value("male"))
        .andExpect(jsonPath("data.staffNo").value("001"));
    assertThat(staffMapper.binding(staffMapper.findByNo("001").getId()).getFaceUuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  /** 用设备请求闩锁稳定复现人员维护等待；独立上传应在设备释放前完成文件和元信息提交。 */
  @Test
  void independentPhotoUploadDoesNotWaitForDeviceSynchronization() throws Exception {
    StaffRequest create = request();
    byte[] image = photo();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    faceDevice.beforeWrite = () -> {
      entered.countDown();
      try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("设备等待测试超时"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Staff> enrollment = pool.submit(() -> staff.create(create));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<String> upload = pool.submit(() -> {
        byte[] body = mvc.perform(multipart("/api/admin/staff/face-images")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "portrait.png", "image/png", image)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(body).path("data").path("imageId").asText();
      });
      String imageId = upload.get(2, TimeUnit.SECONDS);
      assertThat(imageId).isNotBlank();
      assertThat(storage.read(imageId)).isNotEmpty();
      assertThat(staffMapper.count(null, null, null, null)).isZero();
      release.countDown();
      assertThat(enrollment.get(10, TimeUnit.SECONDS).getAvatarId()).isEqualTo(create.getImageId());
    } finally {
      release.countDown();
      pool.shutdown();
      if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
    }
  }

  /** 提交失败时不返回图片ID，已写文件和元信息一起回滚，避免缩短事务后遗留无效图片。 */
  @Test
  void independentPhotoUploadCommitFailureRemovesFileAndMetadata() throws Exception {
    byte[] image = photo();
    java.util.concurrent.atomic.AtomicReference<Path> written = new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(invocation -> {
      Media media = (Media) invocation.callRealMethod(); written.set(storage.resolve(media));
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new BusinessException("模拟头像提交失败"); }
          });
      return media;
    }).when(storage).savePreparedStaffImage(any(byte[].class));
    try {
      mvc.perform(multipart("/api/admin/staff/face-images")
              .file(new org.springframework.mock.web.MockMultipartFile("file", "portrait.png", "image/png", image)))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("模拟头像提交失败"));
      assertThat(written.get()).isNotNull();
      assertThat(Files.exists(written.get())).isFalse();
      assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_media", Integer.class)).isZero();
    } finally { org.mockito.Mockito.reset(storage); }
    assertThat(storage.read(staff.upload(image).getId())).isNotEmpty();
  }

  @Test
  void missingImageSexAndInvalidReferencesNeverWriteToDevice() throws Exception {
    StaffRequest request = request();
    request.setImageId(null);
    assertThatThrownBy(() -> staff.create(request)).hasMessageContaining("imageId");
    request.setImageId(storage.saveImage(photo(), "CAPTURE").getId());
    assertThatThrownBy(() -> staff.create(request)).hasMessageContaining("独立图片上传");
    request.setImageId(Ids.next());
    assertThatThrownBy(() -> staff.create(request)).hasMessageContaining("不存在");
    request.setSex(null);
    mvc.perform(post("/api/admin/staff").contentType(MediaType.APPLICATION_JSON)
             .content(json.writeValueAsBytes(request)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("性别不能为空"));
    assertThat(faceDevice.writes).isEmpty();
  }

  @Test
  void deviceAcknowledgementPrecedesDatabaseAndLifecycleKeepsStableIdentity() {
    java.util.concurrent.atomic.AtomicBoolean earlyWrite = new java.util.concurrent.atomic.AtomicBoolean();
    faceDevice.beforeWrite = () -> {
      if (staffMapper.count(null, null, null, null) != 0) earlyWrite.set(true);
    };
    StaffRequest create = request();
    create.setName("张三<&>");
    Staff saved = staff.create(create);
    assertThat(earlyWrite).isFalse();
    faceDevice.beforeWrite = () -> {};
    String originalUuid = staffMapper.binding(saved.getId()).getFaceUuid();
    assertThat(faceDevice.people.get(originalUuid).get("Name")).isEqualTo(create.getName());
    faceDevice.people.get(originalUuid).put("Country", "中国");
    StaffRequest update = request();
    update.setVersion(saved.getVersion()); update.setImageId(null);
    update.setName("李四&<>"); update.setSex("female");
    Staff changed = staff.update(saved.getId(), update);
    assertThat(changed.getAvatarId()).isEqualTo(saved.getAvatarId());
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).isEqualTo(originalUuid);
    assertThat(faceDevice.people.get(originalUuid)).containsEntry("Name", "李四&<>")
        .containsEntry("Sex", "female").containsEntry("Country", "中国");
    update.setVersion(changed.getVersion()); update.setEnabled(false);
    Staff disabled = staff.update(saved.getId(), update);
    assertThat(faceDevice.people).isEmpty();
    assertThat(staffMapper.binding(saved.getId())).isNull();
    update.setVersion(disabled.getVersion()); update.setEnabled(true);
    Staff enabled = staff.update(saved.getId(), update);
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).isNotEqualTo(originalUuid);
    assertThat(enabled.getAvatarId()).isEqualTo(saved.getAvatarId());
    staff.delete(saved.getId());
    assertThat(faceDevice.people).isEmpty();
    assertThat(staffMapper.find(saved.getId()).getDeleted()).isTrue();
  }

  @Test
  void disabledEnrollmentDoesNotNeedDeviceButEnablingRequiresIt() {
    properties.getDevices().get(0).setPassword("");
    StaffRequest create = request(); create.setEnabled(false);
    Staff disabled = staff.create(create);
    assertThat(staffMapper.binding(disabled.getId())).isNull();
    create.setVersion(disabled.getVersion()); create.setEnabled(true);
    assertThatThrownBy(() -> staff.update(disabled.getId(), create)).hasMessageContaining("password");
    assertThat(staff.require(disabled.getId()).getEnabled()).isFalse();
    assertThat(faceDevice.writes).isEmpty();
  }

  @Test
  void largeStaffPhotosAreScaledForUploadAndExcelWhileCaptureKeepsResolution() throws Exception {
    BufferedImage large = new BufferedImage(1200, 1600, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    ImageIO.write(large, "jpg", original);
    Media uploaded = staff.upload(original.toByteArray());
    byte[] normalized = storage.read(uploaded.getId());
    assertStaffPhoto(normalized, 225, 300);
    StaffRequest create = request(); create.setImageId(uploaded.getId());
    Staff saved = staff.create(create);
    assertThat(faceDevice.photos.get(staffMapper.binding(saved.getId()).getFaceUuid())).isEqualTo(normalized);

    // 同一张大图经过Excel预检后，发送设备和本地存储必须仍为同一份缩放结果。
    byte[] workbookBytes;
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook("", false)));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      int picture = book.addPicture(original.toByteArray(), Workbook.PICTURE_TYPE_JPEG);
      book.getSheetAt(0).createDrawingPatriarch()
          .createPicture(new XSSFClientAnchor(0, 0, 0, 0, 6, 1, 7, 2), picture);
      book.write(output); workbookBytes = output.toByteArray();
    }
    ImportTask task = excel.submit(workbookBytes); excel.process(task);
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("SUCCESS");
    Staff imported = staffMapper.findByNo("002");
    byte[] importedPhoto = storage.read(imported.getAvatarId());
    assertStaffPhoto(importedPhoto, 225, 300);
    assertThat(faceDevice.photos.get(staffMapper.binding(imported.getId()).getFaceUuid())).isEqualTo(importedPhoto);
    BufferedImage capture = ImageIO.read(new ByteArrayInputStream(storage.normalizeImage(original.toByteArray())));
    assertThat(capture.getWidth()).isEqualTo(1200);
    assertThat(capture.getHeight()).isEqualTo(1600);

    // 已上传的旧尺寸不能继续发给设备；明确要求重新上传，避免仍然只得到连接中断。
    create.setImageId(storage.savePreparedStaffImage(original.toByteArray()).getId());
    int writes = faceDevice.writes.size();
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("300×300").hasMessageContaining("重新上传");
    assertThat(faceDevice.writes).hasSize(writes);
  }

  /** PNG采样缩放保持宽高比和白色透明背景；小图不放大，最终仍满足设备JPEG采样要求。 */
  @Test
  void sampledPngStaffPhotosPreserveAspectRatioAndTransparentBackground() throws Exception {
    for (int[] size : new int[][] {{2400, 1600, 300, 200}, {1600, 2400, 200, 300}, {100, 80, 100, 80}}) {
      BufferedImage image = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D graphics = image.createGraphics();
      try {
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(size[0] / 4, size[1] / 4, size[0] / 2, size[1] / 2);
      } finally { graphics.dispose(); }
      ByteArrayOutputStream original = new ByteArrayOutputStream();
      ImageIO.write(image, "png", original);
      byte[] normalized = storage.read(staff.upload(original.toByteArray()).getId());
      assertStaffPhoto(normalized, size[2], size[3]);
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(normalized));
      java.awt.Color background = new java.awt.Color(decoded.getRGB(0, 0));
      assertThat(background.getRed()).isGreaterThan(245);
      assertThat(background.getGreen()).isGreaterThan(245);
      assertThat(background.getBlue()).isGreaterThan(245);
      java.awt.Color center = new java.awt.Color(decoded.getRGB(size[2] / 2, size[3] / 2));
      assertThat(center.getRed()).isGreaterThan(245);
      assertThat(center.getGreen()).isLessThan(10);
      assertThat(center.getBlue()).isLessThan(10);
    }
  }

  /** 验证像素尺寸及JPEG实际采样，而非仅以文件扩展名推断设备要求的YUV 4:2:0。 */
  private void assertStaffPhoto(byte[] photo, int width, int height) throws Exception {
    try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(photo))) {
      javax.imageio.ImageReader reader = ImageIO.getImageReaders(input).next();
      try {
        reader.setInput(input);
        assertThat(reader.getWidth(0)).isEqualTo(width);
        assertThat(reader.getHeight(0)).isEqualTo(height);
        org.w3c.dom.Element metadata = (org.w3c.dom.Element) reader.getImageMetadata(0)
            .getAsTree("javax_imageio_jpeg_image_1.0");
        org.w3c.dom.NodeList components = metadata.getElementsByTagName("componentSpec");
        assertThat(components.getLength()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
          org.w3c.dom.Element component = (org.w3c.dom.Element) components.item(i);
          assertThat(component.getAttribute("HsamplingFactor")).isEqualTo(i == 0 ? "2" : "1");
          assertThat(component.getAttribute("VsamplingFactor")).isEqualTo(i == 0 ? "2" : "1");
        }
      } finally { reader.dispose(); }
    }
  }

  @Test
  void browserStylePhotoUploadPreservesChineseFieldsAndPhotoForCreateAndUpdate() {
    faceDevice.browserMultipartOnly = true;
    StaffRequest create = request(); create.setName("工作人员张三&<>"); create.setSex("female");
    Staff saved = staff.create(create);
    String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    assertThat(faceDevice.people.get(uuid)).containsEntry("Name", create.getName())
        .containsEntry("Sex", create.getSex());
    assertThat(faceDevice.photos.get(uuid)).isEqualTo(storage.read(saved.getAvatarId()));
    StaffRequest update = request(); update.setVersion(saved.getVersion()); update.setName("工作人员李四");
    Staff changed = staff.update(saved.getId(), update);
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).isEqualTo(uuid);
    assertThat(faceDevice.people.get(uuid)).containsEntry("Name", update.getName());
    assertThat(faceDevice.photos.get(uuid)).isEqualTo(storage.read(changed.getAvatarId()));
    assertThat(faceDevice.writes).containsExactly("CREATE:" + uuid, "PHOTO:" + uuid);
  }

  @Test
  void replacingPhotoKeepsOneAvatarAndRejectsAnotherStaffImage() {
    Staff saved = staff.create(request());
    String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    StaffRequest update = request(); update.setVersion(saved.getVersion());
    BufferedImage textured = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 100; x++)
      for (int y = 0; y < 100; y++) textured.setRGB(x, y, (x * 31991 + y * 7919) & 0xffffff);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try { ImageIO.write(textured, "jpg", bytes); }
    catch (IOException e) { throw new IllegalStateException(e); }
    update.setImageId(staff.upload(bytes.toByteArray()).getId());
    String replacement = update.getImageId();
    Staff changed = staff.update(saved.getId(), update);
    assertThat(changed.getAvatarId()).isEqualTo(replacement).isNotEqualTo(saved.getAvatarId());
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).isEqualTo(uuid);
    assertThat(faceDevice.photos.get(uuid)).isEqualTo(storage.read(replacement));
    int calls = faceDevice.writes.size();
    StaffRequest another = request(); another.setImageId(replacement);
    assertThatThrownBy(() -> staff.create(another)).hasMessageContaining("其他工作人员使用");
    assertThat(faceDevice.writes).hasSize(calls);
  }

  @Test
  void rejectedDeviceUpdateLeavesLocalStaffAvatarAndVersionUnchanged() {
    Staff saved = staff.create(request());
    String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    StaffRequest update = request(); update.setVersion(saved.getVersion());
    update.setName("设备拒绝"); faceDevice.rejectedName = update.getName();
    assertThatThrownBy(() -> staff.update(saved.getId(), update)).hasMessageContaining("4112");
    Staff unchanged = staff.require(saved.getId());
    assertThat(unchanged.getName()).isEqualTo(saved.getName());
    assertThat(unchanged.getAvatarId()).isEqualTo(saved.getAvatarId());
    assertThat(unchanged.getVersion()).isEqualTo(saved.getVersion());
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).isEqualTo(uuid);
  }

  /** 复现现场 HTTP 200 但脸库为空：新增和编辑均在写设备前失败，本地资料保持不变。 */
  @Test
  void emptyDeviceFaceGroupsStopCreateAndUpdateBeforeAnyDeviceWrite() {
    Staff saved = staff.create(request());
    faceDevice.writes.clear();
    faceDevice.faceGroupsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><FaceGroups Version=\"1.0\" />";
    StaffRequest create = request();
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("设备未返回任何脸库");
    StaffRequest update = request();
    update.setImageId(null); update.setVersion(saved.getVersion()); update.setName("编辑失败保留原姓名");
    assertThatThrownBy(() -> staff.update(saved.getId(), update)).hasMessageContaining("设备未返回任何脸库");
    assertThat(faceDevice.writes).isEmpty();
    assertThat(staffMapper.count(null, null, null, null)).isEqualTo(1);
    assertThat(staff.require(saved.getId()).getName()).isEqualTo(saved.getName());
    assertThat(staff.require(saved.getId()).getVersion()).isEqualTo(saved.getVersion());
  }

  /** 目标必须匹配 GroupID2，其他脸库存在也不能自动选择；匹配后仍须有数字查询身份。 */
  @Test
  void deviceFaceGroupMismatchAndInvalidNumericIdHaveDistinctDiagnostics() {
    StaffRequest create = request();
    faceDevice.faceGroupsXml = "<FaceGroups><FaceGroup><GroupID>7</GroupID>"
        + "<GroupID2>other-group</GroupID2></FaceGroup></FaceGroups>";
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("配置的脸库不存在", "GroupID2");
    faceDevice.faceGroupsXml = "<FaceGroups><FaceGroup><GroupID>invalid</GroupID>"
        + "<GroupID2>staff-group</GroupID2></FaceGroup></FaceGroups>";
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("缺少有效数字 GroupID");
    assertThat(faceDevice.writes).isEmpty();
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void offlineAuthenticationBadSafetyAndMalformedXmlDoNotCreateStaff() {
    StaffRequest create = request();
    properties.getDevices().get(0).setBaseUrl("http://127.0.0.1:1");
    // 真实连接拒绝应指出预检失败的接口，且不能把设备地址、账号密码返回前端。
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("设备连接失败")
        .hasMessageContaining("接口=/FaceGroups/QueryAll").hasMessageContaining("耗时=")
        .hasMessageNotContaining("127.0.0.1").hasMessageNotContaining("device-password");
    properties.getDevices().get(0).setBaseUrl(faceDevice.url());
    faceDevice.invalidAuthentication = true;
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("401");
    faceDevice.invalidAuthentication = false; faceDevice.invalidXml = true;
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("XML");
    faceDevice.invalidXml = false; properties.getDevices().get(0).setOwnerIdentifier("wrong-owner");
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("安全码不匹配")
        .hasMessageContaining("状态码 4208");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(faceDevice.people).isEmpty();
  }

  /** 用真实 HTTP 业务响应验证各类厂商码，不能将设备 HTTP 200 当作人员维护成功。 */
  @ParameterizedTest
  @CsvSource({
    "1, 操作失败", "2, 操作忙", "3, 操作结束", "-2, 编解码失败",
    "4098, 图片中没有找到人脸", "4099, 图片中人脸数超过一个",
    "4101, 人脸算法未初始化", "4112, 图片像素过大，图片限制在300×300",
    "4208, 安全码不匹配", "16007, 没有权限", "18034, 升级包文件校验失败",
    "99999, 未知厂商错误", "2147483648, 未知厂商错误"
  })
  void vendorErrorsReachHttpResponseWithoutCommittingStaff(String code, String reason) throws Exception {
    rejectDevicePath("/FaceGroup/UpdatePersonInfoAndFaceImage", code);
    String body = mvc.perform(post("/api/admin/staff").contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsBytes(request())))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value(400))
        .andExpect(jsonPath("data").value(org.hamcrest.Matchers.nullValue()))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    String message = json.readTree(body).path("message").asText();
    assertThat(message).contains("设备同步失败：" + reason, "状态码 " + code,
        "接口=/FaceGroup/UpdatePersonInfoAndFaceImage", "本地未提交", "操作ID=")
        .doesNotContain("private-device-data", "device-password", faceDevice.url());
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_staff_device", Integer.class)).isZero();
    assertThat(faceDevice.people).isEmpty();
    assertThat(faceDevice.writes).isEmpty();
  }

  /** 非整数及超长状态码只显示“未知”，不能将状态字段携带的原始文字带入异常和前端。 */
  @ParameterizedTest
  @ValueSource(strings = {"not-an-integer\nprivate-device-data", "99999999999999999999"})
  void invalidVendorCodesAreFilteredBeforeDisplay(String code) {
    rejectDevicePath("/FaceGroups/QueryAll", code);
    assertThatThrownBy(() -> faceClient.people(faceClient.target()))
        .isInstanceOf(DeviceSyncException.class)
        .hasMessage("设备同步失败：未知厂商错误；状态码 未知；接口=/FaceGroups/QueryAll");
    assertThat(faceDevice.writes).isEmpty();
  }

  /** 查询与逐项诊断也走同一解释逻辑，设备自带的 message/requestURL 不参与展示。 */
  @Test
  void diagnosticsKeepEachVendorReasonAndFixedRequestPath() throws Exception {
    rejectDevicePath(null, "-3");
    assertThatThrownBy(() -> faceClient.people(faceClient.target()))
        .hasMessage("设备同步失败：不支持；状态码 -3；接口=/FaceGroups/QueryAll");
    String response = mvc.perform(get("/api/admin/recognition/device-diagnostics"))
        .andExpect(status().isOk()).andExpect(jsonPath("code").value(200))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    JsonNode results = json.readTree(response).path("data");
    assertThat(results.size()).isEqualTo(5);
    Iterator<Map.Entry<String, JsonNode>> entries = results.fields();
    while (entries.hasNext()) {
      Map.Entry<String, JsonNode> entry = entries.next();
      assertThat(entry.getValue().asText())
          .isEqualTo("设备同步失败：不支持；状态码 -3；接口=" + entry.getKey());
    }
    assertThat(response).doesNotContain("private-device-data", "device-password");
    assertThat(faceDevice.writes).isEmpty();
  }

  /** 状态缺失或 XML 无法解析不能证明写入未执行，必须保留原有结果不确定标志。 */
  @ParameterizedTest
  @CsvSource({
    "<ResponseStatus/>, 设备未返回成功状态码",
    "<ResponseStatus><statusCode> </statusCode></ResponseStatus>, 设备未返回成功状态码",
    "not xml, 设备XML响应无效"
  })
  void invalidWriteResponsesRemainUncertain(String response, String reason) {
    faceDevice.responsePath = "/FaceGroup/DeletePersonList";
    faceDevice.responseXml = response;
    DeviceFaceClient.Person person = new DeviceFaceClient.Person();
    person.setGroupId("7"); person.setFaceUuid("never-created");
    assertThatThrownBy(() -> faceClient.delete(faceClient.target(), person))
        .isInstanceOf(DeviceSyncException.class).hasMessageContaining(reason)
        .satisfies(error -> assertThat(((DeviceSyncException) error).isUncertain()).isTrue());
    assertThat(faceDevice.writes).isEmpty();
  }

  /** 带空白的合法码仍能命中定义；明确拒绝的写入不能误标为结果不确定。 */
  @Test
  void explicitVendorWriteRejectionRemainsCertain() {
    rejectDevicePath("/FaceGroup/DeletePersonList", " 4204 ");
    DeviceFaceClient.Person person = new DeviceFaceClient.Person();
    person.setGroupId("7"); person.setFaceUuid("never-created");
    assertThatThrownBy(() -> faceClient.delete(faceClient.target(), person))
        .isInstanceOf(DeviceSyncException.class)
        .hasMessage("设备同步失败：人员 ID 无效；状态码 4204；接口=/FaceGroup/DeletePersonList")
        .satisfies(error -> assertThat(((DeviceSyncException) error).isUncertain()).isFalse());
  }

  private void rejectDevicePath(String path, String code) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("statusCode", code);
    // 刻意夹带敏感文本及非本次请求地址，确保只显示本地定义和固定协议路径。
    fields.put("message", "private-device-data\ndevice-password");
    fields.put("requestURL", "http://device-password@private-device-data/");
    faceDevice.responsePath = path;
    faceDevice.responseXml = DeviceFaceClient.xml("ResponseStatus", fields);
  }

  @Test
  void disconnectedCreateIsQueriedAndCompensatedWithoutReplayingUpload() {
    faceDevice.dropCreateConnection = true;
    assertThatThrownBy(() -> staff.create(request()))
        .hasMessageContaining("接口=/FaceGroup/UpdatePersonInfoAndFaceImage")
        .hasMessageContaining("本地未提交，设备补偿完成");
    assertThat(faceDevice.people).isEmpty();
    assertThat(faceDevice.writes).hasSize(2);
    assertThat(faceDevice.writes.get(0)).startsWith("CREATE:");
    assertThat(faceDevice.writes.get(1)).startsWith("DELETE:");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void uncertainCreateReplyIsQueriedAndUndoneWithoutDuplicateCreation() {
    faceDevice.loseCreateReply = true;
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("设备HTTP状态 500")
        .hasMessageContaining("接口=/FaceGroup/UpdatePersonInfoAndFaceImage");
    assertThat(faceDevice.people).isEmpty();
    assertThat(faceDevice.writes).hasSize(2);
    assertThat(faceDevice.writes.get(0)).startsWith("CREATE:");
    assertThat(faceDevice.writes.get(1)).startsWith("DELETE:");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void timedOutCreateIsNotRetriedAndItsDiscoveredPersonIsRemoved() {
    faceDevice.createReplyDelayMs = 500;
    properties.getDevices().get(0).setReadTimeoutMs(150);
    // 模拟设备已新增但响应延迟，诊断需定位图片写入超时，同时仍只补偿、不能重发新增。
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("设备请求超时")
        .hasMessageContaining("接口=/FaceGroup/UpdatePersonInfoAndFaceImage")
        .hasMessageContaining("读取超时=150ms").hasMessageContaining("本地未提交，设备补偿完成");
    assertThat(faceDevice.people).isEmpty();
    assertThat(faceDevice.writes).hasSize(2);
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void ambiguousDeviceIdentityRequiresReviewAndNeverBindsExistingNames() {
    faceDevice.duplicateCreation = true;
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("设备状态需核对");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_staff_device", Integer.class)).isZero();
    assertThat(faceDevice.writes).hasSize(1);
  }

  @Test
  void nativePeopleWithoutFaceUuidDoNotBlockEnrollmentOrEndPaginationEarly() {
    for (int i = 0; i < 100; i++) faceDevice.people.put("native-" + i, nativePerson(i));
    Map<String, String> existing = nativePerson(100);
    existing.put("FaceUUID", "existing-managed");
    faceDevice.people.put("zz-existing", existing);
    // 第一页全是原生记录时，也必须读到第二页的UUID人员，不能把过滤后的零条当作分页结束。
    assertThat(applicationContext.getBean(DeviceFaceClient.class).people(properties.getDevices().get(0)))
        .containsOnlyKeys("existing-managed");
    Staff saved = staff.create(request());
    String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(faceDevice.people).hasSize(102);
    // 新增前后一整页原生人员不能被绑定、修改或删除；同名同姓也只关联新生成的UUID。
    for (int i = 0; i < 100; i++) assertThat(faceDevice.people.get("native-" + i)).isEqualTo(nativePerson(i));
    assertThat(faceDevice.writes).containsExactly("CREATE:" + uuid);
  }

  @Test
  void compensationPreservesNativePeopleWithoutFaceUuid() {
    Map<String, String> original = nativePerson(0);
    faceDevice.people.put("native-0", original);
    doThrow(new BusinessException("模拟数据库失败")).when(staffMapper).insert(any());
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("本地保存失败")
        .hasMessageContaining("设备补偿完成");
    assertThat(faceDevice.people).containsOnlyKeys("native-0");
    assertThat(faceDevice.people.get("native-0")).isEqualTo(original);
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void duplicateOrUnverifiableDeviceIdentitiesStillStopBeforeWriting() {
    Map<String, String> nativeEntry = nativePerson(0);
    nativeEntry.remove("PersonID"); faceDevice.people.put("invalid", nativeEntry);
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("原生身份无效");
    assertThat(faceDevice.writes).isEmpty();
    faceDevice.people.clear();
    Map<String, String> first = nativePerson(0), second = nativePerson(1);
    first.put("FaceUUID", "same-uuid"); second.put("FaceUUID", "same-uuid");
    faceDevice.people.put("first", first); faceDevice.people.put("second", second);
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("FaceUUID查询分页重复");
    assertThat(faceDevice.writes).isEmpty();
    faceDevice.people.clear();
    faceDevice.people.put("first", nativePerson(0)); faceDevice.people.put("second", nativePerson(0));
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("原生人员ID查询分页重复");
    assertThat(faceDevice.writes).isEmpty();
  }

  /** 模拟现场XML中的原生身份：数字PersonID=0也有效，FaceUUID为空，姓名可以与新增人员相同。 */
  private Map<String, String> nativePerson(int id) {
    Map<String, String> person = new HashMap<>();
    person.put("FaceUUID", ""); person.put("PersonID", String.valueOf(id)); person.put("GroupID", "7");
    person.put("FaceVersion", "0"); person.put("Name", "测试人员"); person.put("Sex", "male");
    return person;
  }

  @Test
  void createSnapshotPaginatesAndDoesNotAdoptExistingSameName() {
    for (int i = 0; i < 100; i++) {
      Map<String, String> person = new HashMap<>();
      person.put("Name", "测试人员"); person.put("Sex", "male");
      faceDevice.people.put("existing-" + i, person);
    }
    Staff saved = staff.create(request());
    assertThat(staffMapper.binding(saved.getId()).getFaceUuid()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(faceDevice.people).hasSize(101);
  }

  @Test
  void databaseCreateFailureRemovesDevicePersonAndLeavesUploadedImageUnbound() {
    StaffRequest create = request();
    doThrow(new BusinessException("模拟数据库失败")).when(staffMapper).insert(any());
    assertThatThrownBy(() -> staff.create(create)).hasMessageContaining("本地保存失败")
        .hasMessageContaining("补偿完成");
    assertThat(faceDevice.people).isEmpty();
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(storage.require(create.getImageId())).isNotNull();
  }

  @Test
  void databaseUpdateFailureRestoresOriginalDeviceInfoPhotoAndIdentity() {
    Staff original = staff.create(request());
    String uuid = staffMapper.binding(original.getId()).getFaceUuid();
    byte[] photo = faceDevice.photos.get(uuid);
    faceDevice.people.get(uuid).put("Country", "中国");
    StaffRequest update = request(); update.setVersion(original.getVersion());
    update.setName("不得保存"); update.setSex("female");
    doThrow(new BusinessException("模拟数据库失败")).when(staffMapper).update(any());
    assertThatThrownBy(() -> staff.update(original.getId(), update)).hasMessageContaining("补偿完成");
    assertThat(faceDevice.people.get(uuid)).containsEntry("Name", original.getName())
        .containsEntry("Sex", "male").containsEntry("Country", "中国");
    assertThat(faceDevice.photos.get(uuid)).isEqualTo(photo);
    assertThat(staff.require(original.getId()).getVersion()).isEqualTo(original.getVersion());
    assertThat(staffMapper.binding(original.getId()).getFaceUuid()).isEqualTo(uuid);
  }

  @Test
  void databaseDeleteFailureRestoresSameDeviceUuidOrExplicitlyReportsCompensationFailure() {
    Staff original = staff.create(request());
    String uuid = staffMapper.binding(original.getId()).getFaceUuid();
    doThrow(new BusinessException("模拟数据库失败")).when(staffMapper).delete(eq(original.getId()), anyInt());
    assertThatThrownBy(() -> staff.delete(original.getId())).hasMessageContaining("补偿完成");
    assertThat(faceDevice.people).containsKey(uuid);
    assertThat(staff.require(original.getId()).getDeleted()).isFalse();
    faceDevice.rejectRestore = true;
    assertThatThrownBy(() -> staff.delete(original.getId())).hasMessageContaining("设备状态需核对");
    assertThat(staff.require(original.getId()).getDeleted()).isFalse();
    assertThat(staffMapper.binding(original.getId()).getFaceUuid()).isEqualTo(uuid);
  }

  @Test
  void deviceNegativeDeleteStatusRemainsVisibleAndLocalStaffIsPreserved() {
    Staff original = staff.create(request());
    String uuid = staffMapper.binding(original.getId()).getFaceUuid();
    faceDevice.deleteStatusCode = "-2";

    assertThatThrownBy(() -> staff.delete(original.getId())).hasMessageContaining("编解码失败")
        .hasMessageContaining("状态码 -2").hasMessageContaining("接口=/FaceGroup/DeletePersonList");
    // 设备明确拒绝删除时不得提交本地逻辑删除，也不得清除设备身份关联。
    assertThat(staff.require(original.getId()).getDeleted()).isFalse();
    assertThat(staffMapper.binding(original.getId()).getFaceUuid()).isEqualTo(uuid);
    assertThat(faceDevice.people).containsKey(uuid);
  }

  @Test
  void deleteUsesNumericGroupIdentityAndPreservesNativeDevicePerson() {
    faceDevice.people.put("native-0", nativePerson(0));
    Staff original = staff.create(request());
    String uuid = staffMapper.binding(original.getId()).getFaceUuid();

    staff.delete(original.getId());

    // 删除已关联的 UUID 人员后，设备原生人员与本地删除状态应分别保持正确。
    assertThat(faceDevice.people).containsKey("native-0").doesNotContainKey(uuid);
    assertThat(staffMapper.find(original.getId()).getDeleted()).isTrue();
    assertThat(staffMapper.binding(original.getId())).isNull();
  }

  @Test
  void deviceAcknowledgedDeleteWithoutRemovalDoesNotCommitLocalDeletion() {
    Staff original = staff.create(request());
    String uuid = staffMapper.binding(original.getId()).getFaceUuid();
    faceDevice.acceptDeleteWithoutChange = true;

    assertThatThrownBy(() -> staff.delete(original.getId())).hasMessageContaining("人员仍在目标脸库");
    assertThat(staff.require(original.getId()).getDeleted()).isFalse();
    assertThat(staffMapper.binding(original.getId()).getFaceUuid()).isEqualTo(uuid);
    assertThat(faceDevice.people).containsKey(uuid);
  }

  @Test
  void staleVersionNeverCallsDevice() {
    Staff saved = staff.create(request());
    int calls = faceDevice.writes.size();
    StaffRequest update = request(); update.setVersion(saved.getVersion() + 1);
    assertThatThrownBy(() -> staff.update(saved.getId(), update)).hasMessageContaining("version");
    assertThat(faceDevice.writes).hasSize(calls);
  }

  private byte[] twoPersonWorkbook() throws Exception {
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook("", true)));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Row row = book.getSheet("工作人员").createRow(2);
      String[] values = {"", "工作人员乙", "岗位", "展厅", "启用", "female"};
      for (int c = 0; c < values.length; c++) row.createCell(c, CellType.STRING).setCellValue(values[c]);
      int image = book.addPicture(photo(), Workbook.PICTURE_TYPE_JPEG);
      book.getSheet("工作人员").createDrawingPatriarch()
          .createPicture(new XSSFClientAnchor(0, 0, 0, 0, 6, 2, 7, 3), image);
      book.write(output); return output.toByteArray();
    }
  }

  @Test
  void excelValidatesAllSexFieldsBeforeAnyDeviceWrite() throws Exception {
    byte[] source;
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(twoPersonWorkbook()));
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      book.getSheet("工作人员").getRow(2).getCell(5).setCellValue("");
      book.write(output); source = output.toByteArray();
    }
    ImportTask task = excel.submit(source); excel.process(task);
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(faceDevice.writes).isEmpty();
    assertThat(staffMapper.count(null, null, null, null)).isZero();
  }

  @Test
  void excelMidBatchDeviceFailureCompensatesPreviouslySyncedRows() throws Exception {
    faceDevice.rejectedName = "工作人员乙";
    ImportTask task = excel.submit(twoPersonWorkbook()); excel.process(task);
    ImportTask failed = imports.find(task.getId());
    assertThat(failed.getStatus()).isEqualTo("FAILED");
    assertThat(failed.getPhase()).isEqualTo("ROLLED_BACK");
    assertThat(json.readTree(failed.getErrors()).get(0).path("row").asInt()).isEqualTo(3);
    assertThat(json.readTree(failed.getErrors()).get(0).path("message").asText())
        .contains("图片像素过大，图片限制在300×300", "状态码 4112",
            "接口=/FaceGroup/UpdatePersonInfoAndFaceImage");
    assertThat(faceDevice.people).isEmpty();
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(failed.getCreatedCount()).isZero();
  }

  @Test
  void excelCompensationFailureIsVisibleAndInterruptedSyncIsNotAutomaticallyRetried() throws Exception {
    faceDevice.rejectedName = "工作人员乙"; faceDevice.rejectDeletes = true;
    ImportTask task = excel.submit(twoPersonWorkbook()); excel.process(task);
    assertThat(imports.find(task.getId()).getPhase()).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(json.readTree(imports.find(task.getId()).getErrors()).get(1).path("row").asInt()).isEqualTo(2);
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    ImportTask interrupted = excel.submit(workbook("", true));
    interrupted.setStatus("PROCESSING"); interrupted.setPhase("SYNCING_DEVICE"); imports.update(interrupted);
    imports.recover();
    assertThat(imports.find(interrupted.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(imports.find(interrupted.getId()).getPhase()).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(imports.pending()).isEmpty();
  }

  /** 在提交前钩子抛错，覆盖所有SQL执行后、事务提交阶段失败的补偿路径。 */
  @Test
  void commitFailureRollsBackStaffAndBindingThenCompensatesDevice() {
    failBeforeStaffCommit();
    assertThatThrownBy(() -> staff.create(request())).hasMessageContaining("本地保存失败");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_staff_device", Integer.class)).isZero();
    assertThat(faceDevice.people).isEmpty();
  }

  private void failBeforeStaffCommit() {
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
              throw new BusinessException("模拟事务提交失败");
            }
          });
      return result;
    }).when(staff).save(any());
  }

  @Test
  void excelCommitFailureDoesNotPublishSuccessWhileCompensating() throws Exception {
    failBeforeStaffCommit();
    ImportTask task = excel.submit(workbook("", true));
    java.util.concurrent.atomic.AtomicBoolean observedCompensation = new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicBoolean prematureSuccess = new java.util.concurrent.atomic.AtomicBoolean();
    faceDevice.beforeWrite = () -> {
      ImportTask current = imports.find(task.getId());
      if ("COMPENSATING".equals(current.getPhase())) {
        observedCompensation.set(true);
        if (!"PROCESSING".equals(current.getStatus()) || current.getCreatedCount() != 0)
          prematureSuccess.set(true);
      }
    };
    excel.process(task);
    assertThat(observedCompensation).isTrue();
    assertThat(prematureSuccess).isFalse();
    assertThat(imports.find(task.getId()).getStatus()).isEqualTo("FAILED");
    assertThat(staffMapper.count(null, null, null, null)).isZero();
    assertThat(faceDevice.people).isEmpty();
  }

  @Test
  void concurrentEditorsCannotOverwriteDeviceUsingTheSameVersion() throws Exception {
    Staff saved = staff.create(request());
    StaffRequest first = request(); first.setVersion(saved.getVersion()); first.setName("第一位编辑");
    StaffRequest second = request(); second.setVersion(saved.getVersion()); second.setName("第二位编辑");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    faceDevice.beforeWrite = () -> {
      entered.countDown();
      try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("编辑测试超时"); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Staff> accepted = pool.submit(() -> staff.update(saved.getId(), first));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Future<Staff> rejected = pool.submit(() -> staff.update(saved.getId(), second));
      release.countDown();
      assertThat(accepted.get(10, TimeUnit.SECONDS).getName()).isEqualTo(first.getName());
      assertThatThrownBy(() -> rejected.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
      String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
      assertThat(faceDevice.people.get(uuid).get("Name")).isEqualTo(first.getName());
      assertThat(faceDevice.writes).hasSize(2);
    } finally { release.countDown(); pool.shutdownNow(); }
  }

  /** 模拟契约仅验证本地逻辑，不证明现场uniqueId等于FaceUUID。 */
  private Staff recognizedStaff() throws Exception {
    ExhibitionProperties.Recognition config = properties.getRecognition();
    config.setProtocolVerified(true); config.setAckVerified(true);
    config.setIdentityField("uniqueId"); config.setIdentitySource("FACE_UUID");
    config.setGroupValue("staff-group"); config.setMinSimilarity(new java.math.BigDecimal("80"));
    Staff saved = staff.create(request());
    config.setEnabled(true); recognition.activate();
    db.update("UPDATE exh_device SET recognition_started_at=? WHERE serial_number='CAM-TEST'", DeviceIdentityService.now().minusDays(2));
    db.update("UPDATE exh_device_identity SET valid_from=?", DeviceIdentityService.now().minusDays(2));
    return saved;
  }

  private com.fasterxml.jackson.databind.node.ObjectNode reco(String uuid, int count, LocalDateTime time) throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode root = json.createObjectNode();
    root.put("operator", "FaceReco"); root.putObject("deviceInfo").put("serialNumber", "CAM-TEST");
    com.fasterxml.jackson.databind.node.ObjectNode info = root.putObject("info");
    info.put("eventId", UUID.randomUUID().toString()); info.put("time", time.toString());
    info.put("matchNumber", count); info.put("direction", "none");
    info.putObject("CaptureImage").put("picture", Base64.getEncoder().encodeToString(photo()));
    if (count > 0) info.putObject("personInfo").put("personId", "123").put("uniqueId", uuid)
        .put("personType", "staff-group").put("match", "88.083099").put("serial", 0);
    else info.putObject("personInfo");
    return root;
  }

  private Capture receiveReco(JsonNode root) {
    devices.receive(properties.getDevices().get(0), root);
    return events.list(null, null, null, 0, 1).get(0);
  }

  @Test
  void deviceJpgDataUrlsAndEmptyReferenceImageReachStaffOverview() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    com.fasterxml.jackson.databind.node.ObjectNode root = reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1));
    com.fasterxml.jackson.databind.node.ObjectNode info = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("info");
    byte[] image = photo();
    // 复现现场固件：抓拍/背景使用 image/jpg，未返回库图时仍保留空 Data URL 和空内容 MD5。
    ((com.fasterxml.jackson.databind.node.ObjectNode) info.path("CaptureImage"))
        .put("picture", "data:image/jpg;base64," + Base64.getEncoder().encodeToString(image))
        .put("pictureMd5", DeviceAuthentication.hex("MD5", image));
    info.set("BackgroundImage", info.path("CaptureImage").deepCopy());
    info.putObject("recognizeImage").put("picture", "data:image/jpg;base64,")
        .put("pictureLength", 0).put("base64PicLength", 22)
        .put("pictureMd5", "d41d8cd98f00b204e9800998ecf8427e");
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(root)))
        .andExpect(status().isOk()).andExpect(jsonPath("operator").value("FaceReco-Ack"));
    Capture event = events.list(null, null, null, 0, 1).get(0);
    assertThat(event.getMatchStatus()).isEqualTo("STAFF");
    assertThat(event.getBackgroundId()).isNotNull();
    assertThat(event.getRecognizeImageId()).isNull();
    assertThat(event.getPictureMd5()).isEqualTo(DeviceAuthentication.hex("MD5", image));
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.staffCount").value(1))
        .andExpect(jsonPath("data.latestStaff.staff.id").value(saved.getId()));
  }

  /** 使用确定的单位向量验证身份事务；真实模型另外使用现场照片验证，不用模拟分数证明精度。 */
  private void useFaceFeature(double... coordinates) {
    properties.getFaceFeature().setEnabled(true);
    double[] vector = new double[128]; double sum = 0;
    for (int i = 0; i < coordinates.length; i++) { vector[i] = coordinates[i]; sum += vector[i] * vector[i]; }
    double norm = Math.sqrt(sum);
    for (int i = 0; i < vector.length; i++) vector[i] /= norm;
    doReturn(vector).when(faceFeatureClient).extract(any(byte[].class));
  }

  @Test
  void dailyPeopleCountIncludesStaffAndDeduplicatesAnonymousVisitors() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    useFaceFeature(1, 0);
    Capture employee = receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1)));
    assertThat(faceFeatureMapper.event(employee.getId()).getStatus()).isEqualTo("PENDING");
    verify(faceFeatureClient, never()).extract(any(byte[].class));
    assertThat(faceFeatureMapper.queued(properties.getHallId(), "CAM-TEST", properties.getFaceFeature().getModelId(),
        DeviceIdentityService.now(), 3, 10)).hasSize(1);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("PROCESSING"))
        .andExpect(jsonPath("data.totalCount").value(0));
    faceFeatures.process(employee);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.totalCount").value(1))
        .andExpect(jsonPath("data.guestCount").value(0)).andExpect(jsonPath("data.staffCount").value(1));
    useFaceFeature(0, 1);
    Capture first = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    Capture repeated = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    faceFeatures.process(first); faceFeatures.process(repeated); faceFeatures.process(repeated);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("AVAILABLE"))
        .andExpect(jsonPath("data.countMode").value("PEOPLE_DAILY_DISTINCT"))
        .andExpect(jsonPath("data.totalCount").value(2)).andExpect(jsonPath("data.guestCount").value(1))
        .andExpect(jsonPath("data.staffCount").value(1));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_face_feature_event", Integer.class)).isEqualTo(3);
  }

  @Test
  void visitorLaterConfirmedAsStaffMergesAndKeepsEarlierPoseTemplate() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    useFaceFeature(1, 0);
    faceFeatures.process(receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1))));
    useFaceFeature(0, 1);
    faceFeatures.process(receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1))));
    assertThat(faceFeatureMapper.counts(properties.getHallId(), DeviceIdentityService.now().toLocalDate(),
        properties.getFaceFeature().getModelId()).getTotalCount()).isEqualTo(2);
    // 模拟工作人员的新视角：已确认staffId保持不变，同时将相似匿名身份归并进来。
    useFaceFeature(1, 1);
    faceFeatures.process(receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1))));
    useFaceFeature(0, 1);
    faceFeatures.process(receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1))));
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.totalCount").value(1))
        .andExpect(jsonPath("data.guestCount").value(0)).andExpect(jsonPath("data.staffCount").value(1));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_person_daily WHERE merged_into IS NOT NULL", Integer.class)).isEqualTo(1);
  }

  @Test
  void differentConfirmedStaffCannotMergeBecauseTheirFeaturesAreSimilar() throws Exception {
    Staff first = recognizedStaff();
    StaffRequest secondRequest = request(); secondRequest.setName("第二名工作人员");
    Staff second = staff.create(secondRequest);
    useFaceFeature(1, 0);
    faceFeatures.process(receiveReco(reco(staffMapper.binding(first.getId()).getFaceUuid(), 1, DeviceIdentityService.now().minusSeconds(1))));
    faceFeatures.process(receiveReco(reco(staffMapper.binding(second.getId()).getFaceUuid(), 1, DeviceIdentityService.now().minusNanos(1000000))));
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.totalCount").value(2))
        .andExpect(jsonPath("data.staffCount").value(2)).andExpect(jsonPath("data.guestCount").value(0));
  }

  @Test
  void anonymousIdentityResetsAtBeijingMidnight() throws Exception {
    recognizedStaff(); useFaceFeature(0, 1);
    Capture yesterday = receiveReco(reco("", 0, DeviceIdentityService.now().minusDays(1)));
    faceFeatures.process(yesterday);
    faceFeatures.process(receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1))));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_person_daily", Integer.class)).isEqualTo(2);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.totalCount").value(1))
        .andExpect(jsonPath("data.guestCount").value(1));
  }

  @Test
  void failedFeatureServiceDoesNotInventVisitorAndCanRecoverAfterRetry() throws Exception {
    recognizedStaff(); useFaceFeature(0, 1);
    Capture capture = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    doThrow(new FaceFeatureClient.ExtractionFailure("FEATURE_SERVICE_UNAVAILABLE", true))
        .when(faceFeatureClient).extract(any(byte[].class));
    for (int attempt = 0; attempt < 3; attempt++) faceFeatures.process(capture);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("PARTIAL"))
        .andExpect(jsonPath("data.personCountFailed").value(1))
        .andExpect(jsonPath("data.totalCount").value(0)).andExpect(jsonPath("data.guestCount").value(0));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_person_daily", Integer.class)).isZero();
    mvc.perform(post("/api/admin/recognition/face-features/retry")).andExpect(status().isOk())
        .andExpect(jsonPath("data.retriedCount").value(1));
    useFaceFeature(0, 1); faceFeatures.process(capture);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("AVAILABLE"))
        .andExpect(jsonPath("data.totalCount").value(1)).andExpect(jsonPath("data.guestCount").value(1));
  }

  @Test
  void featureTransactionFailureRollsBackPersonAndKeepsRetryableTask() throws Exception {
    recognizedStaff(); useFaceFeature(0, 1);
    Capture capture = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    doThrow(new BusinessException("模拟特征结果提交失败")).when(faceFeatureMapper)
        .saveEvent(argThat(event -> event != null && "READY".equals(event.getStatus())));
    faceFeatures.process(capture);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_person_daily", Integer.class)).isZero();
    assertThat(faceFeatureMapper.event(capture.getId()).getStatus()).isEqualTo("FAILED");
    mvc.perform(get("/api/admin/captures/{id}", capture.getId())).andExpect(status().isOk())
        .andExpect(jsonPath("data.faceFeature.status").value("FAILED"))
        .andExpect(jsonPath("data.faceFeature.error").value("FEATURE_PROCESSING_FAILED"))
        .andExpect(jsonPath("data.faceFeature.feature").doesNotExist());
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.totalCount").value(0))
        .andExpect(jsonPath("data.personCountStatus").value("PROCESSING"));
  }

  @Test
  void existingPeopleCountsSurviveQueuedAndFailedCapture() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    useFaceFeature(1, 0);
    faceFeatures.process(receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1))));
    useFaceFeature(0, 1);
    faceFeatures.process(receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1))));
    Capture unresolved = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    // 模拟页面已有统计后又收到新抓拍：排队期间保留2人，不能退回null或清零。
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("PROCESSING"))
        .andExpect(jsonPath("data.totalCount").value(2)).andExpect(jsonPath("data.guestCount").value(1))
        .andExpect(jsonPath("data.personCountPending").value(1));
    doThrow(new FaceFeatureClient.ExtractionFailure("NO_FACE", false))
        .when(faceFeatureClient).extract(any(byte[].class));
    faceFeatures.process(unresolved);
    // 失败样本保留诊断，但不作为新人计数，也不隐藏之前已经成功去重的工作人员和访客。
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("PARTIAL"))
        .andExpect(jsonPath("data.totalCount").value(2)).andExpect(jsonPath("data.guestCount").value(1))
        .andExpect(jsonPath("data.staffCount").value(1)).andExpect(jsonPath("data.personCountFailed").value(1));
    useFaceFeature(0, 1); faceFeatures.retryFailed(); faceFeatures.process(unresolved);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.personCountStatus").value("AVAILABLE"))
        .andExpect(jsonPath("data.totalCount").value(2)).andExpect(jsonPath("data.guestCount").value(1));
  }

  @Test
  void concurrentAnonymousEventsStillCreateOneDailyPerson() throws Exception {
    recognizedStaff(); useFaceFeature(0, 1);
    Capture first = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    Capture second = receiveReco(reco("", 0, DeviceIdentityService.now().minusSeconds(1)));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> one = pool.submit(() -> faceFeatures.process(first));
      Future<?> two = pool.submit(() -> faceFeatures.process(second));
      one.get(10, TimeUnit.SECONDS); two.get(10, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_person_daily", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_face_feature_event WHERE status='READY'", Integer.class)).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jpg", "jpeg", "png"})
  void emptyRequiredCaptureDataUrlIsStillRejected(String format) throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode root = reco("any-person", 1, DeviceIdentityService.now().minusSeconds(1));
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("info").path("CaptureImage"))
        .put("picture", "data:image/" + format + ";base64,");
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(root)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("设备图片大小无效"));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isZero();
  }

  @Test
  void recognitionStoresStaffSnapshotDeduplicatesAndExposesTimedCard() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    com.fasterxml.jackson.databind.node.ObjectNode root = reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1));
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("info")).set("recognizeImage", root.path("info").path("CaptureImage").deepCopy());
    Capture first = receiveReco(root);
    Map<String, Object> firstAck = devices.receive(properties.getDevices().get(0), root);
    assertThat(firstAck.get("operator")).isEqualTo("FaceReco-Ack");
    assertThat(first.getMatchStatus()).isEqualTo("STAFF"); assertThat(first.getStaffId()).isEqualTo(saved.getId());
    assertThat(first.getProtocolJson()).doesNotContain("\"picture\"");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_media WHERE owner_type='CAPTURE'", Integer.class)).isEqualTo(1);
    receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusNanos(1000000)));
    mvc.perform(get("/api/screen/overview")).andExpect(status().isOk())
        .andExpect(jsonPath("data.staffCount").value(1)).andExpect(jsonPath("data.guestCount").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("data.countMode").value("STAFF_DAILY_DISTINCT"))
        .andExpect(jsonPath("data.latestStaff.staff.id").value(saved.getId()))
        .andExpect(jsonPath("data.latestStaff.displayUntil").value(org.hamcrest.Matchers.endsWith("+08:00")));
    mvc.perform(get("/api/admin/captures/{id}", first.getId())).andExpect(status().isOk())
        .andExpect(jsonPath("data.staff.name").value(saved.getName()))
        .andExpect(jsonPath("data.protocol.info.CaptureImage.picture").doesNotExist());
    mvc.perform(get("/api/admin/captures").param("eventType", "FaceReco").param("matchStatus", "STAFF").param("staffId", saved.getId()))
        .andExpect(status().isOk()).andExpect(jsonPath("data.length()").value(2));
    db.update("UPDATE exh_capture_event SET event_time=?", DeviceIdentityService.now().minusSeconds(11));
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.latestStaff").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("data.staffCount").value(1));
  }

  @Test
  void unknownMultipleInvalidAndNoMatchNeverBecomeStaff() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    assertThat(receiveReco(reco("native-device-person", 1, DeviceIdentityService.now().minusSeconds(1))).getMatchStatus()).isEqualTo("UNRESOLVED");
    assertThat(receiveReco(reco(uuid, 0, DeviceIdentityService.now().minusSeconds(1))).getMatchStatus()).isEqualTo("NO_MATCH");
    assertThat(receiveReco(reco(uuid, 2, DeviceIdentityService.now().minusSeconds(1))).getMatchStatus()).isEqualTo("REVIEW");
    com.fasterxml.jackson.databind.node.ObjectNode low = reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1));
    ((com.fasterxml.jackson.databind.node.ObjectNode) low.path("info").path("personInfo")).put("match", "79.99");
    assertThat(receiveReco(low).getMatchStatus()).isEqualTo("REVIEW");
    ((com.fasterxml.jackson.databind.node.ObjectNode) low.path("info").path("personInfo")).put("match", "NaN");
    ((com.fasterxml.jackson.databind.node.ObjectNode) low.path("info")).put("eventId", "invalid-score");
    assertThat(receiveReco(low).getMatchStatus()).isEqualTo("REVIEW");
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.staffCount").value(0));
  }

  @Test
  void delayedIdentityMappingRetriesAtMostThreeThenWakeOnBaseline() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    db.update("DELETE FROM exh_device_identity");
    Capture event = receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1)));
    for (int i = 0; i < 4; i++) {
      db.update("UPDATE exh_capture_event SET next_resolve_at=? WHERE id=?", DeviceIdentityService.now().minusSeconds(1), event.getId());
      recognition.resolve(event.getId(), true);
    }
    assertThat(events.find(event.getId()).getResolveAttempts()).isEqualTo(3);
    assertThat(events.find(event.getId()).getMatchStatus()).isEqualTo("UNRESOLVED");
    staff.refreshDeviceBaseline();
    assertThat(events.find(event.getId()).getResolveAttempts()).isZero();
    assertThat(events.find(event.getId()).getNextResolveAt()).isNotNull();
    // 基线不推测过去；此处显式模拟设备写入早于回调、本地事务稍后提交的合法区间。
    db.update("UPDATE exh_device_identity SET valid_from=?", event.getEventTime().minusSeconds(1));
    mvc.perform(post("/api/admin/captures/{id}/resolve", event.getId())).andExpect(status().isOk())
        .andExpect(jsonPath("data.matchStatus").value("STAFF"));
    assertThat(events.find(event.getId()).getStaffId()).isEqualTo(saved.getId());
  }

  @Test
  void deletedStaffRetainsHistoricalIdentityAndAvatarReference() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    LocalDateTime occurred = DeviceIdentityService.now().minusSeconds(1);
    staff.delete(saved.getId());
    Capture event = receiveReco(reco(uuid, 1, occurred));
    assertThat(event.getMatchStatus()).isEqualTo("STAFF");
    assertThat(event.getStaffAvatarId()).isEqualTo(saved.getAvatarId());
    assertThat(mediaMapper.unused(DeviceIdentityService.now().plusDays(1))).extracting(Media::getId).doesNotContain(saved.getAvatarId());
    Capture afterRemoval = receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusNanos(1000000)));
    assertThat(afterRemoval.getMatchStatus()).isEqualTo("UNRESOLVED");
  }

  @Test
  void previousDayAndExpiredEventsNeverReshowCardOrCountToday() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    Capture yesterday = receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusDays(1)));
    assertThat(yesterday.getMatchStatus()).isEqualTo("STAFF");
    assertThat(events.staffCount(properties.getHallId(), yesterday.getBusinessDate(), events.recognitionStarted("CAM-TEST"))).isEqualTo(1);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.staffCount").value(0))
        .andExpect(jsonPath("data.latestStaff").value(org.hamcrest.Matchers.nullValue()));
    Capture expired = receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusDays(31)));
    assertThat(expired.getStatus()).isEqualTo("EXPIRED"); assertThat(expired.getImageId()).isNull();
  }

  @Test
  void unverifiedAckPersistsDiagnosticThenReturns503AndVerifiedRetransmissionIsStable() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    properties.getRecognition().setEnabled(false); properties.getRecognition().setAckVerified(false);
    JsonNode root = reco(uuid, 0, DeviceIdentityService.now().minusSeconds(1));
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(root)))
        .andExpect(status().isServiceUnavailable());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isEqualTo(1);
    properties.getRecognition().setAckVerified(true);
    Map<String, Object> ack = devices.receive(properties.getDevices().get(0), root);
    properties.getRecognition().setAckPersonIdType("INTEGER");
    assertThat(devices.receive(properties.getDevices().get(0), root)).isEqualTo(ack);
  }

  @Test
  void concurrentRecoRetransmissionsPersistSingleEventAndMedia() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    byte[] body = json.writeValueAsBytes(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1)));
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> requests = new ArrayList<>();
      for (int i = 0; i < 4; i++) requests.add(pool.submit(() -> mvc.perform(post("/api/device/p6s/events")
          .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus()));
      for (Future<Integer> result : requests) assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(200);
    } finally { pool.shutdownNow(); }
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isEqualTo(1);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_media WHERE owner_type='CAPTURE'", Integer.class)).isEqualTo(1);
  }

  @Test
  void startedAtSurvivesRestartAndTargetHeartbeatDoesNotUseOtherCamera() throws Exception {
    recognizedStaff(); LocalDateTime started = events.recognitionStarted("CAM-TEST");
    recognition.activate(); assertThat(events.recognitionStarted("CAM-TEST")).isEqualTo(started);
    properties.getRecognition().setMinSimilarity(java.math.BigDecimal.ZERO);
    assertThatThrownBy(recognition::activate).hasMessageContaining("契约未完整验收");
    properties.getRecognition().setMinSimilarity(new java.math.BigDecimal("80"));
    events.heartbeat("OTHER-CAMERA", DeviceIdentityService.now());
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.cameraOnline").value(false));
    events.heartbeat("CAM-TEST", DeviceIdentityService.now());
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.cameraOnline").value(true));
    properties.getRecognition().setMinSimilarity(null);
    assertThatThrownBy(recognition::activate).hasMessageContaining("契约未完整验收");
  }

  /** SQL和文件均已写入但提交失败时，事务撤销元数据，文件同步回滚；重传可重新完成。 */
  @Test
  void recognitionCommitFailureRemovesFilesAndRetransmissionRecovers() throws Exception {
    Staff saved = recognizedStaff();
    JsonNode root = reco(staffMapper.binding(saved.getId()).getFaceUuid(), 1, DeviceIdentityService.now().minusSeconds(1));
    java.util.concurrent.atomic.AtomicReference<Path> written = new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(invocation -> {
      Media media = (Media) invocation.callRealMethod(); written.set(storage.resolve(media));
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new BusinessException("模拟识别提交失败"); }
          });
      return media;
    }).when(storage).saveImage(any(byte[].class), eq("CAPTURE"));
    mvc.perform(post("/api/device/p6s/events").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(root)))
        .andExpect(status().isBadRequest());
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_media WHERE owner_type='CAPTURE'", Integer.class)).isZero();
    assertThat(Files.exists(written.get())).isFalse();
    org.mockito.Mockito.reset(storage);
    assertThat(receiveReco(root).getMatchStatus()).isEqualTo("STAFF");
  }

  @Test
  void laterImageFailureRollsBackAlreadySavedCapture() throws Exception {
    Staff saved = recognizedStaff();
    com.fasterxml.jackson.databind.node.ObjectNode root = reco(staffMapper.binding(saved.getId()).getFaceUuid(), 1, DeviceIdentityService.now().minusSeconds(1));
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("info")).set("recognizeImage", root.path("info").path("CaptureImage").deepCopy());
    java.util.concurrent.atomic.AtomicReference<Path> written = new java.util.concurrent.atomic.AtomicReference<>();
    doAnswer(invocation -> { Media media = (Media) invocation.callRealMethod(); written.set(storage.resolve(media)); return media; })
        .when(storage).saveImage(any(byte[].class), eq("CAPTURE"));
    doThrow(new BusinessException("模拟磁盘不可写")).when(storage).saveImage(any(byte[].class), eq("RECOGNIZE"));
    assertThatThrownBy(() -> devices.receive(properties.getDevices().get(0), root)).hasMessageContaining("磁盘");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isZero();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_media WHERE owner_type='CAPTURE'", Integer.class)).isZero();
    assertThat(Files.exists(written.get())).isFalse();
  }

  @Test
  void snapshotAndRecognitionAreSeparateAndOlderArrivalCannotReplaceLatestCard() throws Exception {
    Staff saved = recognizedStaff(); String uuid = staffMapper.binding(saved.getId()).getFaceUuid();
    com.fasterxml.jackson.databind.node.ObjectNode root = reco(uuid, 1, DeviceIdentityService.now().minusSeconds(1));
    Capture latest = receiveReco(root);
    com.fasterxml.jackson.databind.node.ObjectNode snapshot = root.deepCopy(); snapshot.put("operator", "FaceSnapshot");
    assertThat(receiveReco(snapshot).getMatchStatus()).isEqualTo("NOT_COMPARED");
    receiveReco(reco(uuid, 1, DeviceIdentityService.now().minusSeconds(5)));
    assertThat(db.queryForObject("SELECT COUNT(*) FROM exh_capture_event", Integer.class)).isEqualTo(3);
    mvc.perform(get("/api/screen/overview")).andExpect(jsonPath("data.staffCount").value(1))
        .andExpect(jsonPath("data.latestStaff.captureId").value(latest.getId()));
    StaffRequest update = request(); update.setVersion(saved.getVersion()); update.setName("更新后的姓名");
    staff.update(saved.getId(), update);
    assertThat(recognition.resolve(latest.getId(), false).getStaffName()).isEqualTo(saved.getName());
  }

  @Test
  void baselineReadsWithoutDeviceWritesAndStartupRejectsChangedIdentitySource() throws Exception {
    Staff saved = recognizedStaff();
    LocalDateTime from = identities.active(saved.getId()).get(0).getValidFrom();
    int writes = faceDevice.writes.size();
    assertThat(staff.refreshDeviceBaseline()).isEqualTo(1);
    assertThat(faceDevice.writes).hasSize(writes);
    assertThat(identities.active(saved.getId()).get(0).getValidFrom()).isEqualTo(from);
    db.update("UPDATE exh_device_identity SET identity_value='corrupted-identity'");
    assertThatThrownBy(recognition::activate).hasMessageContaining("基线");
    staff.refreshDeviceBaseline();
    properties.getRecognition().setIdentitySource("PERSON_ID");
    assertThatThrownBy(recognition::activate).hasMessageContaining("基线");
  }

}
