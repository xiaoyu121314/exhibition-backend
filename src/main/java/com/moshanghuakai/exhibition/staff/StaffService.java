package com.moshanghuakai.exhibition.staff;

import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.StaffMapper;
import com.moshanghuakai.exhibition.device.DeviceIdentityService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 单实例人员维护：全部校验、同步设备、再提交数据库；单条和批量共用补偿。 @author MoShangHuaKai */
@Slf4j
@Service
public class StaffService {
  private final StaffMapper mapper;
  private final StorageService storage;
  private final TransactionTemplate transaction;
  private final StaffDeviceSynchronizer synchronizer;
  private final Validator validator;
  private final DeviceIdentityService identities;
  private final ReentrantLock maintenance = new ReentrantLock(true);

  public StaffService(StaffMapper mapper, StorageService storage, TransactionTemplate transaction,
      StaffDeviceSynchronizer synchronizer, Validator validator, DeviceIdentityService identities) {
    this.mapper = mapper;
    this.storage = storage;
    this.transaction = transaction;
    this.synchronizer = synchronizer;
    this.validator = validator;
    this.identities = identities;
  }

  /** 维护锁覆盖预检、设备请求、数据库提交及补偿，防止单条与导入交错覆盖设备资料。 */
  public <T> T withMaintenance(Supplier<T> action) {
    maintenance.lock();
    try { return action.get(); }
    finally { maintenance.unlock(); }
  }

  public Staff require(String id) {
    Staff staff = mapper.find(id);
    if (staff == null || Boolean.TRUE.equals(staff.getDeleted()))
      throw new BusinessException("工作人员不存在或已删除");
    return staff;
  }

  public Map<String, Object> deviceBinding(String id) { return identities.bindingView(id); }

  /** 与单条及Excel维护共用锁，防止设备快照查询和人员写入交错。 */
  public int refreshDeviceBaseline() { return withMaintenance(identities::baseline); }

  public Map<String, Object> page(String no, String name, String department, Boolean enabled,
      int page, int size) {
    if (page < 1 || size < 1 || size > 100 || page > 1000000)
      throw new BusinessException("分页参数无效，每页最多100条");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("total", mapper.count(no, name, department, enabled));
    result.put("records", mapper.list(no, name, department, enabled, (page - 1) * size, size));
    return result;
  }

  public Staff create(StaffRequest request) {
    return withMaintenance(() -> {
      Staff staff = fromValidated(request);
      staff.setId(Ids.next());
      assignStaffNumbers(Collections.singletonList(staff));
      if (request.getImageId() == null) throw new BusinessException("新增工作人员必须提供 imageId");
      Change change = prepare(null, staff, request.getImageId(), null, 0);
      execute(Collections.singletonList(change), phase -> {}, () -> {});
      return require(staff.getId());
    });
  }

  public Staff update(String id, StaffRequest request) {
    return withMaintenance(() -> {
      Staff before = require(id);
      if (request.getVersion() == null || !before.getVersion().equals(request.getVersion()))
        throw new BusinessException("修改时必须提供最新 version，请刷新后重试");
      Staff after = fromValidated(request);
      after.setId(id);
      // 工号由后台建档时分配，修改资料时始终保留原号。
      after.setStaffNo(before.getStaffNo());
      after.setAvatarId(before.getAvatarId());
      Change change = prepare(before, after, request.getImageId(), null, 0);
      execute(Collections.singletonList(change), phase -> {}, () -> {});
      return require(id);
    });
  }

  public void delete(String id) {
    withMaintenance(() -> {
      Staff before = require(id);
      Change change = new Change();
      change.before = before;
      byte[] oldPhoto = storage.read(before.getAvatarId());
      change.device = synchronizer.prepare(before, null, mapper.binding(id), oldPhoto, null, false);
      execute(Collections.singletonList(change), phase -> {}, () -> {});
      return null;
    });
  }

  /**
   * 独立上传不修改人员或设备，不占用人员维护锁，避免被设备慢响应、批量导入阻塞。
   * 图片校验和压缩先在事务外完成，仅保存文件与元信息时开启事务，保留提交失败后的文件清理。
   * 新图片未满24小时，不会被过期清理选中；绑定人员时仍由维护锁保护引用检查和设备同步。
   */
  public Media upload(byte[] bytes) {
    long started = System.nanoTime();
    byte[] normalized = storage.normalizeStaffImage(bytes);
    long prepared = System.nanoTime();
    Media image = transaction.execute(status -> storage.savePreparedStaffImage(normalized));
    long completed = System.nanoTime();
    // 保存阶段包含获取数据库连接、写文件、写元信息及事务提交；不记录人脸内容或原始文件名。
    log.info("工作人员头像上传完成，原图={}B，处理后={}B，图片处理={}ms，保存提交={}ms，总耗时={}ms",
        bytes.length, normalized.length, TimeUnit.NANOSECONDS.toMillis(prepared - started),
        TimeUnit.NANOSECONDS.toMillis(completed - prepared),
        TimeUnit.NANOSECONDS.toMillis(completed - started));
    return image;
  }

  /** 导入完成整份格式、性别和图片预检后再次核对版本；设备动作前不写文件或业务数据。 */
  public Change prepareImported(StaffExcelService.ImportRow row) {
    Staff after = row.getStaff();
    Staff current = mapper.findByNo(after.getStaffNo());
    if (row.isNewStaff() ? current != null
        : current == null || Boolean.TRUE.equals(current.getDeleted())
            || !current.getId().equals(after.getId()) || !current.getVersion().equals(after.getVersion()))
      throw new BusinessException("第" + row.getRowNumber() + "行人员在导入期间被修改，整批未提交");
    return prepare(current, after, null, row.getImage(), row.getRowNumber());
  }

  public static class Change {
    private Staff before, after;
    private String imageId;
    private byte[] photo;
    private int row;
    private StaffDeviceSynchronizer.Change device;
  }

  /**
   * 单条和Excel新增共用编号规则：从001开始，至少三位，超过999后自然增长。
   * 调用者必须持有维护锁，并持续持有到设备同步及数据库提交结束；同批人员依次分配，
   * 已有工号保持不变。最大值包含已删除人员，失败且未提交的编号不会占用。
   */
  public void assignStaffNumbers(List<Staff> staff) {
    if (!maintenance.isHeldByCurrentThread()) throw new IllegalStateException("分配工号必须持有维护锁");
    long number = mapper.maxStaffNo();
    for (Staff person : staff) {
      if (person.getStaffNo() != null) continue;
      if (number == Long.MAX_VALUE) throw new BusinessException("工号已达到上限，无法新增工作人员");
      person.setStaffNo(String.format(Locale.ROOT, "%03d", ++number));
    }
  }

  private Change prepare(Staff before, Staff after, String imageId, byte[] photo, int row) {
    Staff duplicate = mapper.findByNo(after.getStaffNo());
    if (duplicate != null && !duplicate.getId().equals(after.getId()))
      throw new DuplicateKeyException("工号已存在");
    Change change = new Change();
    change.before = before;
    change.after = after;
    change.row = row;
    change.imageId = imageId;
    if (imageId != null) {
      Media media = storage.require(imageId);
      if (!"STAFF".equals(media.getOwnerType()) || !"image/jpeg".equals(media.getContentType()))
        throw new BusinessException("imageId 必须来自工作人员独立图片上传接口");
      if (mapper.imageOwners(imageId, after.getId()) > 0)
        throw new BusinessException("图片已被其他工作人员使用");
      photo = storage.read(imageId);
      // 相同图片ID表示保留头像，不把资料修改误判成换照片。
      if (before != null && imageId.equals(before.getAvatarId())) photo = null;
    }
    if (photo != null) {
      // imageId 对应独立上传已规范化的文件；导入照片已在整份预检阶段规范化。
      // 同一份字节用于发送设备及落盘，不能在各阶段反复JPEG压缩。
      if (photo.length == 0 || photo.length > 5 * 1024 * 1024)
        throw new BusinessException("照片为空或超过5MB");
      change.photo = photo;
    }
    if (before == null && change.photo == null) throw new BusinessException("新增工作人员必须提供有效头像");
    byte[] oldPhoto = before == null ? null : storage.read(before.getAvatarId());
    byte[] newPhoto = change.photo == null ? oldPhoto : change.photo;
    if (newPhoto == null) throw new BusinessException("工作人员缺少可用头像");
    change.device = synchronizer.prepare(before, after, mapper.binding(after.getId()),
        oldPhoto, newPhoto, change.photo != null);
    return change;
  }

  /**
   * 调用者持有维护锁。先执行全部设备动作，成功后才启动本地事务；提交异常也进入补偿。
   * 导入成功标记通过 completed 与人员数据一并提交，失败阶段记录独立于工作人员事务。
   */
  public void execute(List<Change> changes, Consumer<String> phase, Runnable completed) {
    if (!maintenance.isHeldByCurrentThread()) throw new IllegalStateException("人员维护必须持有维护锁");
    List<Change> attempted = new ArrayList<>();
    int row = 0;
    String operationId = Ids.next();
    boolean databaseStage = false;
    try {
      phase.accept("SYNCING_DEVICE");
      for (Change change : changes) {
        row = change.row;
        attempted.add(change);
        change.device.apply();
      }
      databaseStage = true;
      phase.accept("COMMITTING");
      transaction.execute(status -> {
        for (Change change : changes) save(change);
        completed.run();
        return null;
      });
    } catch (Exception failure) {
      boolean compensationFailed = false;
      List<Map<String, Object>> compensationErrors = new ArrayList<>();
      try { phase.accept("COMPENSATING"); }
      catch (Exception ignored) { log.warn("无法更新补偿阶段，操作ID={}", operationId); }
      for (int i = attempted.size() - 1; i >= 0; i--) {
        Change change = attempted.get(i);
        try { change.device.undo(); }
        catch (Exception undoFailure) {
          compensationFailed = true;
          String undoReason = undoFailure instanceof BusinessException
              ? undoFailure.getMessage() : "设备补偿请求异常";
          Map<String, Object> error = new LinkedHashMap<>();
          error.put("row", change.row);
          error.put("message", "设备补偿失败：" + undoReason + "；操作ID=" + operationId);
          compensationErrors.add(error);
          log.error("设备补偿失败，操作ID={}，行号={}，人员ID={}，原因={}", operationId, change.row,
              change.before == null ? change.after.getId() : change.before.getId(), undoReason);
        }
      }
      String reason = databaseStage ? "本地保存失败"
          : failure instanceof BusinessException ? failure.getMessage() : "设备同步失败";
      String result = compensationFailed ? "本地未提交，设备状态需核对" : "本地未提交，设备补偿完成";
      log.warn("人员维护失败，操作ID={}，阶段={}，补偿失败={}，原因={}", operationId,
          databaseStage ? "DATABASE" : "DEVICE", compensationFailed, reason);
      throw new MaintenanceFailure(reason + "；" + result + "；操作ID=" + operationId,
          databaseStage ? 0 : row, compensationFailed, compensationErrors);
    }
  }

  /** 只在设备全部成功后的事务内调用，单条和整批使用相同的照片和设备身份关联规则。 */
  public void save(Change change) {
    Staff after = change.after;
    if (after == null) {
      if (mapper.delete(change.before.getId(), change.before.getVersion()) != 1)
        throw new BusinessException("人员已被其他请求修改，请刷新后重试");
      mapper.deleteBinding(change.before.getId());
      identities.maintain(null, change.device.removedAt(), change.before.getId());
      return;
    }
    if (change.photo != null) {
      if (change.imageId == null) change.imageId = storage.savePreparedStaffImage(change.photo).getId();
      after.setAvatarId(change.imageId);
    }
    if (change.before == null) mapper.insert(after);
    else {
      if (mapper.update(after) != 1) throw new BusinessException("人员已被其他请求修改，请刷新后重试");
      after.setVersion(after.getVersion() + 1);
    }
    mapper.deleteBinding(after.getId());
    StaffDeviceBinding binding = change.device.result();
    identities.maintain(binding, change.device.removedAt(), after.getId());
    if (binding != null) mapper.insertBinding(binding);
  }

  public static class MaintenanceFailure extends BusinessException {
    private final int row;
    private final boolean compensationFailed;
    private final List<Map<String, Object>> compensationErrors;
    public MaintenanceFailure(String message, int row, boolean compensationFailed,
        List<Map<String, Object>> compensationErrors) {
      super(message);
      this.row = row;
      this.compensationFailed = compensationFailed;
      this.compensationErrors = compensationErrors;
    }
    public int getRow() { return row; }
    public boolean isCompensationFailed() { return compensationFailed; }
    public List<Map<String, Object>> getCompensationErrors() { return compensationErrors; }
  }

  private Staff fromValidated(StaffRequest request) {
    Set<javax.validation.ConstraintViolation<StaffRequest>> violations = validator.validate(request);
    if (!violations.isEmpty()) throw new BusinessException(violations.iterator().next().getMessage());
    return from(request);
  }

  public Staff from(StaffRequest r) {
    Staff staff = new Staff();
    staff.setName(r.getName().trim());
    staff.setSex(r.getSex());
    staff.setPosition(r.getPosition().trim());
    staff.setDepartment(r.getDepartment() == null ? "" : r.getDepartment().trim());
    staff.setEnabled(r.getEnabled());
    staff.setVersion(r.getVersion() == null ? 0 : r.getVersion());
    return staff;
  }
}
