package com.moshanghuakai.exhibition.staff;

import com.moshanghuakai.exhibition.config.ExhibitionProperties.Device;
import com.moshanghuakai.exhibition.device.*;
import com.moshanghuakai.exhibition.device.DeviceFaceClient.Person;
import com.moshanghuakai.exhibition.domain.Models.*;
import java.util.*;
import java.time.*;
import org.springframework.stereotype.Service;

/** 为一次人员变更保留设备前态和逆操作；不写数据库，不安排自动重试。 @author MoShangHuaKai */
@Service
public class StaffDeviceSynchronizer {
  private final DeviceFaceClient client;

  public StaffDeviceSynchronizer(DeviceFaceClient client) { this.client = client; }

  public Change prepare(Staff before, Staff after, StaffDeviceBinding binding,
      byte[] oldPhoto, byte[] newPhoto, boolean photoChanged) {
    boolean enabled = after != null && Boolean.TRUE.equals(after.getEnabled());
    if (before != null && Boolean.TRUE.equals(before.getEnabled()) && binding == null)
      throw new DeviceSyncException("工作人员缺少设备身份关联，请核对设备与本地资料", false);
    String mode = enabled ? (binding == null ? "CREATE" : photoChanged ? "PHOTO" : "MODIFY")
        : binding == null ? "NONE" : "DELETE";
    Device target = "NONE".equals(mode) ? null : client.target();
    if (binding != null && (!binding.getDeviceSerialNumber().equals(target.getSerialNumber())
        || !binding.getFaceGroupId().equals(target.getFaceGroupId())))
      throw new DeviceSyncException("当前设备配置与人员原设备关联不一致", false);
    return new Change(mode, target, after, binding, oldPhoto, newPhoto);
  }

  public class Change {
    private final String mode;
    private final Device target;
    private final Staff after;
    private final StaffDeviceBinding original;
    private final byte[] oldPhoto, newPhoto;
    private Map<String, Person> snapshot;
    private Person originalPerson;
    private Person resultPerson;
    private LocalDateTime startedAt, removedAt;
    private String createdUuid;
    private boolean needsUndo;

    private Change(String mode, Device target, Staff after,
        StaffDeviceBinding original, byte[] oldPhoto, byte[] newPhoto) {
      this.mode = mode;
      this.target = target;
      this.after = after;
      this.original = original;
      this.oldPhoto = oldPhoto;
      this.newPhoto = newPhoto;
    }

    /** 先完成只读快照，再标记可能产生副作用的请求；查询失败不会触发不必要的逆操作。 */
    public void apply() {
      if ("NONE".equals(mode)) return;
      snapshot = client.people(target);
      if (original != null) {
        originalPerson = snapshot.get(original.getFaceUuid());
        if (originalPerson == null)
          throw new DeviceSyncException("设备人员不存在，不能确认原身份", false);
      }
      needsUndo = true;
      startedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
      boolean acknowledged = false;
      try {
        switch (mode) {
          case "CREATE":
            client.putPhoto(target, UUID.randomUUID().toString(), after, newPhoto);
            acknowledged = true;
            createdUuid = createdPerson(client.people(target));
            if (createdUuid == null)
              throw new DeviceSyncException("新增后无法确认唯一FaceUUID，设备状态需核对", true);
            break;
          case "PHOTO":
            client.putPhoto(target, original.getFaceUuid(), after, newPhoto);
            break;
          case "MODIFY":
            client.modify(target, original.getFaceUuid(), after, originalPerson);
            break;
          case "DELETE":
            client.delete(target, originalPerson);
            // 固件可能对未命中的 FaceUUID 也返回成功；确认目标脸库中确已移除后才提交本地删除。
            if (client.people(target).containsKey(original.getFaceUuid()))
              throw new DeviceSyncException("设备返回成功但人员仍在目标脸库，设备状态需核对", true);
            break;
          default: throw new IllegalStateException("未知设备操作");
        }
        if ("DELETE".equals(mode)) removedAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        else {
          resultPerson = client.people(target).get(original == null ? createdUuid : original.getFaceUuid());
          if (resultPerson == null) throw new DeviceSyncException("同步后无法回读设备身份及建模状态", true);
        }
      } catch (DeviceSyncException e) {
        // 明确的业务拒绝没有可补偿的成功结果；超时、无效响应仍保留逆操作资格。
        if (!e.isUncertain() && !acknowledged) needsUndo = false;
        throw e;
      }
    }

    public StaffDeviceBinding result() {
      if ("DELETE".equals(mode) || "NONE".equals(mode)) return null;
      StaffDeviceBinding binding = new StaffDeviceBinding();
      binding.setStaffId(after.getId());
      binding.setDeviceSerialNumber(target.getSerialNumber());
      binding.setFaceGroupId(target.getFaceGroupId());
      binding.setFaceUuid(original == null ? createdUuid : original.getFaceUuid());
      binding.setNumericGroupId(resultPerson.getGroupId());
      binding.setDevicePersonId(resultPerson.getPersonId());
      binding.setDeviceUniqueId(resultPerson.getUniqueId());
      binding.setModelState(resultPerson.getModelState());
      binding.setVerifiedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
      // 相同身份由历史服务沿用旧起点；身份改变时使用本次设备写入起点，不能套用旧身份区间。
      binding.setValidFrom(startedAt);
      return binding;
    }

    /** 删除区间只在设备确认移除后关闭，设备失败时整个本地事务不会提交。 */
    public LocalDateTime removedAt() { return removedAt; }

    /**
     * 逆操作前查询确认结果：新增只删除本次出现的唯一人员；修改/删除必须恢复原FaceUUID。
     * 设备可能不允许用旧ID重建，遇到这种情况明确补偿失败，不偷偷换ID使本地关联失效。
     */
    public void undo() {
      if (!needsUndo) return;
      Map<String, Person> current = client.people(target);
      if ("CREATE".equals(mode)) {
        String uuid = createdUuid == null ? createdPerson(current) : createdUuid;
        if (uuid == null)
          throw new DeviceSyncException("新增请求结果不确定，无法安全补偿，设备状态需核对", true);
        if (current.containsKey(uuid)) client.delete(target, current.get(uuid));
        if (client.people(target).containsKey(uuid))
          throw new DeviceSyncException("撤销新增后设备人员仍存在", true);
      } else {
        // 超时也先查询；删除已被设备明确拒绝的请求不会进入本方法。
        String uuid = original.getFaceUuid();
        Person person = current.get(uuid);
        Staff restore = new Staff();
        restore.setName(originalPerson.getName());
        restore.setSex(originalPerson.getSex());
        if ("MODIFY".equals(mode) && sameInfo(person, originalPerson)) return;
        if ("DELETE".equals(mode) && sameInfo(person, originalPerson)) return;
        client.putPhoto(target, uuid, restore, oldPhoto);
        client.modify(target, uuid, restore, originalPerson);
        if (!sameInfo(client.people(target).get(uuid), originalPerson))
          throw new DeviceSyncException("无法恢复原设备FaceUUID及资料，设备状态需核对", true);
      }
      needsUndo = false;
    }

    private String createdPerson(Map<String, Person> current) {
      List<Person> candidates = new ArrayList<>();
      for (Person person : current.values())
        if (!snapshot.containsKey(person.getFaceUuid()) && after.getName().equals(person.getName())
            && after.getSex().equals(person.getSex())) candidates.add(person);
      // 名字只是二次核对条件，身份必须是快照中不存在的设备新ID。
      return candidates.size() == 1 ? candidates.get(0).getFaceUuid() : null;
    }

    private boolean sameInfo(Person first, Person second) {
      return first != null && Objects.equals(first.getName(), second.getName())
          && Objects.equals(first.getSex(), second.getSex())
          && Objects.equals(first.getCertificateType(), second.getCertificateType())
          && Objects.equals(first.getCertificateId(), second.getCertificateId())
          && Objects.equals(first.getCountry(), second.getCountry())
          && Objects.equals(first.getAddress(), second.getAddress())
          && Objects.equals(first.getBirthday(), second.getBirthday());
    }
  }
}
