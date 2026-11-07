package com.moshanghuakai.exhibition.staff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Excel 图片以绘图锚点关联行；先逐行校验，再同步设备并原子提交整批，人员写入复用 StaffService。 @author MoShangHuaKai */
@Slf4j
@Service
public class StaffExcelService {
  private static final String[] HEADERS = {"工号", "姓名", "职位", "部门", "状态", "性别", "头像"};
  private final StaffMapper staffMapper;
  private final ImportMapper tasks;
  private final StaffService staffService;
  private final StorageService storage;
  private final ExhibitionProperties properties;
  private final ObjectMapper json;
  private final Validator validator;
  private final AtomicBoolean busy = new AtomicBoolean();

  public StaffExcelService(
      StaffMapper staffMapper,
      ImportMapper tasks,
      StaffService staffService,
      StorageService storage,
      ExhibitionProperties properties,
      ObjectMapper json,
      Validator validator) {
    this.staffMapper = staffMapper;
    this.tasks = tasks;
    this.staffService = staffService;
    this.storage = storage;
    this.properties = properties;
    this.json = json;
    this.validator = validator;
  }

  @Data
  @Schema(name = "StaffExportFilter", description = "工作人员导出筛选条件，省略字段表示不按该条件过滤")
  public static class Filter {
    @Schema(description = "工号模糊匹配，字符串保留前导零", example = "001")
    private String staffNo;

    @Schema(description = "姓名模糊匹配", example = "张三")
    private String name;

    @Schema(description = "部门精确匹配", example = "展厅")
    private String department;

    @Schema(description = "是否启用，null 表示不过滤")
    private Boolean enabled;
  }

  @Data
  public static class ImportRow {
    private int rowNumber;
    private Staff staff;
    private boolean newStaff;
    private byte[] image;
  }

  @Transactional
  public ImportTask submit(byte[] bytes) {
    if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K')
      throw new BusinessException("请使用下载的 .xlsx 模板，不支持 .xls 或其他文件");
    Media media = storage.saveWorkbook(bytes);
    ImportTask task = new ImportTask();
    task.setId(Ids.next());
    task.setFileId(media.getId());
    task.setStatus("PENDING");
    task.setPhase("UPLOADED");
    tasks.insert(task);
    return tasks.find(task.getId());
  }

  public ImportTask requireTask(String id) {
    ImportTask task = tasks.find(id);
    if (task == null) throw new BusinessException("导入任务不存在或已到期");
    return task;
  }

  public byte[] template() {
    return workbook(Collections.emptyList(), true);
  }

  public byte[] export(Filter filter) {
    List<Staff> staff =
        staffMapper.list(
            filter.staffNo,
            filter.name,
            filter.department,
            filter.enabled,
            0,
            properties.getMaxExportRows() + 1);
    if (staff.size() > properties.getMaxExportRows())
      throw new BusinessException("导出人数超过限制，请缩小筛选范围");
    return workbook(staff, false);
  }
  /** 导出与模板共用完全相同的列、文本格式和图片锚点，保证导出文件可再导入。 */
  private byte[] workbook(List<Staff> staff, boolean empty) {
    try (XSSFWorkbook book = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      XSSFSheet sheet = book.createSheet("工作人员");
      sheet.createFreezePane(0, 1);
      CellStyle text = book.createCellStyle();
      text.setDataFormat(book.createDataFormat().getFormat("@"));
      CellStyle header = book.createCellStyle();
      Font font = book.createFont();
      font.setBold(true);
      header.setFont(font);
      Row title = sheet.createRow(0);
      for (int c = 0; c < HEADERS.length; c++) {
        title.createCell(c).setCellValue(HEADERS[c]);
        title.getCell(c).setCellStyle(header);
        sheet.setColumnWidth(c, (c == 6 ? 20 : 22) * 256);
        sheet.setDefaultColumnStyle(c, text);
      }
      DataValidationHelper helper = sheet.getDataValidationHelper();
      DataValidation validation =
          helper.createValidation(
              helper.createExplicitListConstraint(new String[] {"启用", "停用"}),
              new CellRangeAddressList(
                  1, Math.max(properties.getMaxImportRows(), staff.size()), 4, 4));
      validation.setShowErrorBox(true);
      sheet.addValidationData(validation);
      DataValidation sexValidation = helper.createValidation(
          helper.createExplicitListConstraint(new String[] {"male", "female"}),
          new CellRangeAddressList(1, Math.max(properties.getMaxImportRows(), staff.size()), 5, 5));
      sexValidation.setShowErrorBox(true);
      sheet.addValidationData(sexValidation);
      XSSFDrawing drawing = sheet.createDrawingPatriarch();
      for (int i = 0; i < staff.size(); i++) {
        Staff person = staff.get(i);
        Row row = sheet.createRow(i + 1);
        row.setHeightInPoints(90);
        String[] values = {
          person.getStaffNo(),
          person.getName(),
          person.getPosition(),
          person.getDepartment(),
          Boolean.TRUE.equals(person.getEnabled()) ? "启用" : "停用",
          person.getSex()
        };
        for (int c = 0; c < values.length; c++) {
          Cell cell = row.createCell(c, CellType.STRING);
          cell.setCellStyle(text);
          cell.setCellValue(values[c] == null ? "" : values[c]);
        }
        if (person.getAvatarId() != null) {
          byte[] image = storage.read(person.getAvatarId());
          int index = book.addPicture(image, Workbook.PICTURE_TYPE_JPEG);
          XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, 6, i + 1, 7, i + 2);
          anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
          drawing.createPicture(anchor, index);
        }
      }
      if (empty) {
        Row row = sheet.createRow(1);
        row.setHeightInPoints(90);
        for (int c = 0; c < 6; c++) {
          Cell cell = row.createCell(c, CellType.STRING);
          cell.setCellStyle(text);
        }
      }
      XSSFSheet instructions = book.createSheet("填写说明");
      String[] notes = {
        "新增时工号留空，后台从001起自动递增；修改时填写已有工号，以文本保存并保留前导零。",
        "姓名、职位、状态、性别必填；姓名最多32字符。",
        "性别填写 male（男）或 female（女），与设备要求一致。",
        "状态填写启用或停用。部门留空在更新时表示清空部门。",
        "请使用 Excel 插入图片并放在头像列对应人员行，设置为随单元格移动和缩放。每行最多一张图。",
        "模板读取标准绘图嵌入图片；IMAGE/DISPIMG 公式、单元格富数据图片、图片链接及分组图片不支持，请转换为标准插入图片。",
        "新工号必须有清晰单人头像。已有工号无头像时保留旧照片；有头像时替换唯一头像。",
        "填写工号只更新已有人员，不存在、重复及已删除工号报错。任意一行失败，整批不更新。",
        "整份校验通过后同步设备，再整批写库；失败时尝试逆向补偿设备，补偿失败必须人工核对。",
        "文件限制50MB，单图5MB，默认每批最多" + properties.getMaxImportRows() + "人。",
        "空白模板不包含示例人员，避免示例数据被误导入。"
      };
      for (int i = 0; i < notes.length; i++)
        instructions.createRow(i).createCell(0).setCellValue(notes[i]);
      instructions.setColumnWidth(0, 120 * 256);
      book.write(output);
      if (output.size() > 50 * 1024 * 1024) throw new BusinessException("导出文件超过50MB，请缩小筛选范围");
      return output.toByteArray();
    } catch (IOException e) {
      throw new BusinessException("Excel 生成失败");
    }
  }

  @Scheduled(initialDelay = 5000, fixedDelay = 1500)
  public void processPending() {
    if (!properties.isJobsEnabled()) return;
    if (!busy.compareAndSet(false, true)) return;
    try {
      for (ImportTask task : tasks.pending()) if (tasks.claim(task.getId()) == 1) process(task);
    } finally {
      busy.set(false);
    }
  }

  public void process(ImportTask task) {
    staffService.withMaintenance(() -> {
      processLocked(task);
      return null;
    });
  }

  /** 共用人员维护锁，确保整批预检、设备同步、数据库提交和补偿期间不夹入单条编辑。 */
  private void processLocked(ImportTask task) {
    task.setStatus("PROCESSING");
    task.setPhase("VALIDATING");
    // 重启后从源文件重新校验整批；清零旧进度，不能把上次已校验行数再次累加。
    task.setTotalRows(0);
    task.setProcessedRows(0);
    task.setCreatedCount(0);
    task.setUpdatedCount(0);
    task.setErrors("[]");
    List<Map<String, Object>> errors = new ArrayList<>();
    try {
      List<ImportRow> rows = parse(storage.read(task.getFileId()));
      task.setTotalRows(rows.size());
      tasks.update(task);
      for (ImportRow row : rows) {
        try {
          if (row.image != null) {
              // 与独立上传共用300×300内的设备照片规格，后续同步和落盘复用同一份JPEG字节。
              row.image = storage.normalizeStaffImage(row.image);
          }
        } catch (RuntimeException e) {
          errors.add(error(row.rowNumber, safeMessage(e)));
        }
        task.setProcessedRows(task.getProcessedRows() + 1);
        tasks.update(task);
      }
      if (!errors.isEmpty()) throw new BusinessException("存在人员或头像错误，整批未提交");
      List<StaffService.Change> changes = new ArrayList<>();
      for (ImportRow row : rows) changes.add(staffService.prepareImported(row));
      staffService.execute(changes,
          phase -> {
            // completed 回调可能已修改内存对象但随后提交失败；补偿期间不能发布 SUCCESS。
            task.setStatus("PROCESSING");
            if ("COMPENSATING".equals(phase)) {
              task.setCreatedCount(0);
              task.setUpdatedCount(0);
            }
            task.setPhase(phase);
            tasks.update(task);
          },
          () -> {
            task.setCreatedCount((int) rows.stream().filter(ImportRow::isNewStaff).count());
            task.setUpdatedCount(rows.size() - task.getCreatedCount());
            task.setStatus("SUCCESS");
            task.setPhase("COMPLETED");
            task.setErrors("[]");
            tasks.update(task);
          });
    } catch (Exception e) {
      StaffService.MaintenanceFailure maintenanceFailure = e instanceof StaffService.MaintenanceFailure
          ? (StaffService.MaintenanceFailure) e : null;
      if (errors.isEmpty()) errors.add(error(maintenanceFailure == null ? 0 : maintenanceFailure.getRow(), safeMessage(e)));
      if (maintenanceFailure != null) errors.addAll(maintenanceFailure.getCompensationErrors());
      task.setStatus("FAILED");
      task.setPhase(maintenanceFailure != null && maintenanceFailure.isCompensationFailed()
          ? "RECONCILIATION_REQUIRED" : "ROLLED_BACK");
      task.setCreatedCount(0);
      task.setUpdatedCount(0);
      task.setErrors(writeJson(errors));
      tasks.update(task);
      log.warn("人员导入未提交，任务ID={}，原因={}", task.getId(), safeMessage(e));
    }
  }

  /** 只接受模板约定的标准绘图图片；无法理解的图片结构报错，防止错误关联到其他人的行。 */
  public List<ImportRow> parse(byte[] bytes) {
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      XSSFSheet sheet = book.getSheet("工作人员");
      if (sheet == null) throw new BusinessException("缺少工作人员工作表，请下载模板");
      if (sheet.getNumMergedRegions() > 0) throw new BusinessException("工作人员工作表不能合并单元格");
      DataFormatter format = new DataFormatter(Locale.CHINA);
      for (int c = 0; c < HEADERS.length; c++)
        if (sheet.getRow(0) == null
            || !HEADERS[c].equals(format.formatCellValue(sheet.getRow(0).getCell(c)).trim()))
          throw new BusinessException("模板列不正确，请重新下载模板");
      if (sheet.getLastRowNum() > properties.getMaxImportRows())
        throw new BusinessException("导入行数超过限制，请拆分文件并删除尾部多余行");
      if (book.getAllPictures().size() > (properties.getMaxImportRows() + 5))
        throw new BusinessException("工作簿图片数量超过限制");
      // 富数据和单元格内嵌图片不在标准 drawing 锚点中，必须明确拒绝，避免更新时被当成空头像。
      for (org.apache.poi.openxml4j.opc.PackagePart part : book.getPackage().getParts()) {
        String partName = part.getPartName().getName();
        if (partName.startsWith("/xl/richData/") || partName.contains("cellimages"))
          throw new BusinessException("发现不支持的单元格图片，请按模板说明转换为标准插入图片");
      }
      Map<Integer, byte[]> pictures = new HashMap<>();
      XSSFDrawing drawing = sheet.getDrawingPatriarch();
      if (drawing != null)
        for (XSSFShape shape : drawing.getShapes()) {
          if (!(shape instanceof XSSFPicture))
            throw new BusinessException("头像区域存在分组图片或其他绘图，请仅保留标准插入图片");
          XSSFPicture picture = (XSSFPicture) shape;
          XSSFClientAnchor anchor = picture.getClientAnchor();
          if (anchor == null
              || anchor.getCol1() != 6
              || anchor.getRow1() < 1
              || anchor.getRow1() > sheet.getLastRowNum())
            throw new BusinessException("图片必须锚定到头像列及有效人员行");
          if (pictures.put(anchor.getRow1(), picture.getPictureData().getData()) != null)
            throw new BusinessException("第" + (anchor.getRow1() + 1) + "行存在多张头像");
        }
      List<ImportRow> result = new ArrayList<>();
      Set<String> numbers = new HashSet<>();
      for (int r = 1; r <= sheet.getLastRowNum(); r++) {
        Row excelRow = sheet.getRow(r);
        if (excelRow == null) {
          if (pictures.containsKey(r)) throw new BusinessException("第" + (r + 1) + "行图片没有对应人员");
          continue;
        }
        String[] values = new String[6];
        boolean blank = true;
        for (int c = 0; c < 6; c++) {
          Cell cell = excelRow.getCell(c);
          if (cell != null && cell.getCellType() == CellType.FORMULA)
            throw new BusinessException("第" + (r + 1) + "行不允许公式");
          values[c] = format.formatCellValue(cell).trim();
          if (!values[c].isEmpty()) blank = false;
        }
        if (blank) {
          if (pictures.containsKey(r)) throw new BusinessException("第" + (r + 1) + "行图片没有对应人员");
          continue;
        }
        Cell avatarCell = excelRow.getCell(6);
        if (avatarCell != null && !format.formatCellValue(avatarCell).trim().isEmpty())
          throw new BusinessException("第" + (r + 1) + "行头像列请插入图片，不要填写公式或链接");
        // 新增行允许省略工号单元格；更新行仍要求文本，确保001不会被Excel改成1。
        if (!values[0].isEmpty() && excelRow.getCell(0).getCellType() != CellType.STRING)
          throw new BusinessException("第" + (r + 1) + "行工号必须是文本单元格，避免前导零丢失");
        if (!values[0].isEmpty() && !numbers.add(values[0].toLowerCase(Locale.ROOT)))
          throw new BusinessException("第" + (r + 1) + "行工号重复");
        if (!"启用".equals(values[4]) && !"停用".equals(values[4]))
          throw new BusinessException("第" + (r + 1) + "行状态必须填写启用或停用");
        StaffRequest request = new StaffRequest();
        request.setName(values[1]);
        request.setSex(values[5]);
        request.setPosition(values[2]);
        request.setDepartment(values[3]);
        request.setEnabled("启用".equals(values[4]));
        Set<ConstraintViolation<StaffRequest>> violations = validator.validate(request);
        if (!violations.isEmpty())
          throw new BusinessException(
              "第" + (r + 1) + "行：" + violations.iterator().next().getMessage());
        Staff existing = values[0].isEmpty() ? null : staffMapper.findByNo(values[0]);
        if (!values[0].isEmpty() && existing == null)
          throw new BusinessException("第" + (r + 1) + "行工号不存在，新增人员请留空工号");
        if (existing != null && Boolean.TRUE.equals(existing.getDeleted()))
          throw new BusinessException("第" + (r + 1) + "行工号已删除，不能自动恢复");
        ImportRow row = new ImportRow();
        row.rowNumber = r + 1;
        row.newStaff = existing == null;
        row.staff = staffService.from(request);
        row.staff.setStaffNo(existing == null ? null : existing.getStaffNo());
        row.staff.setId(existing == null ? Ids.next() : existing.getId());
        row.staff.setVersion(existing == null ? 0 : existing.getVersion());
        row.staff.setAvatarId(existing == null ? null : existing.getAvatarId());
        row.image = pictures.get(r);
        if (row.newStaff && row.image == null)
          throw new BusinessException("第" + (r + 1) + "行新人员必须提供头像");
        result.add(row);
      }
      if (result.isEmpty()) throw new BusinessException("模板中没有可导入的工作人员");
      // 完整解析后统一分配，多个新增空工号不会相互重复；生产导入全程持有人员维护锁。
      staffService.assignStaffNumbers(result.stream().map(ImportRow::getStaff).collect(Collectors.toList()));
      return result;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException("Excel 读取失败，请使用下载的标准 .xlsx 模板");
    }
  }

  private Map<String, Object> error(int row, String message) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("row", row);
    error.put("message", message);
    return error;
  }

  private String safeMessage(Exception e) {
    return e instanceof BusinessException ? e.getMessage() : "处理失败，请检查设备状态和后台日志";
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
