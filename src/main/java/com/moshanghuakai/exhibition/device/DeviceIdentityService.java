package com.moshanghuakai.exhibition.device;

import com.moshanghuakai.exhibition.common.*;
import com.moshanghuakai.exhibition.config.ExhibitionProperties;
import com.moshanghuakai.exhibition.config.ExhibitionProperties.Recognition;
import com.moshanghuakai.exhibition.domain.Models.*;
import com.moshanghuakai.exhibition.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 身份来源显式配置，人员维护和基线回查共用一套历史区间规则。 @author MoShangHuaKai */
@Service
public class DeviceIdentityService {
  private final ExhibitionProperties properties;
  private final IdentityMapper identities;
  private final StaffMapper staff;
  private final EventMapper events;
  private final DeviceFaceClient client;
  private final TransactionTemplate transaction;

  public DeviceIdentityService(ExhibitionProperties properties, IdentityMapper identities,
      StaffMapper staff, EventMapper events, DeviceFaceClient client, TransactionTemplate transaction) {
    this.properties = properties; this.identities = identities; this.staff = staff;
    this.events = events; this.client = client; this.transaction = transaction;
  }

  public static LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
  public static OffsetDateTime offset(LocalDateTime value) {
    return value == null ? null : value.atOffset(ZoneOffset.ofHours(8));
  }

  /** 仅使用配置选定的来源。FaceUUID映射到uniqueId也必须先有真机证据。 */
  public void selectIdentity(StaffDeviceBinding binding) {
    Recognition config = properties.getRecognition();
    binding.setIdentityType(null); binding.setIdentityValue(null); binding.setCallbackGroup(null);
    if (!config.isProtocolVerified()) return;
    String source = config.getIdentitySource();
    String value = "FACE_UUID".equals(source) ? binding.getFaceUuid()
        : "PERSON_ID".equals(source) ? binding.getDevicePersonId()
        : "UNIQUE_ID".equals(source) ? binding.getDeviceUniqueId() : null;
    if (value == null || value.trim().isEmpty() || config.getIdentityField() == null
        || config.getGroupValue() == null) return;
    binding.setIdentityType(config.getIdentityField());
    binding.setIdentityValue(value);
    binding.setCallbackGroup(config.getGroupValue());
  }

  /** 调用者处在人员事务内；相同身份沿用原区间，禁止每次改职位都重建起点。 */
  public void maintain(StaffDeviceBinding binding, LocalDateTime until, String staffId) {
    List<DeviceIdentity> active = identities.active(staffId);
    if (binding == null) { identities.close(staffId, until == null ? now() : until); return; }
    selectIdentity(binding);
    if (binding.getIdentityValue() == null) return;
    boolean same = active.size() == 1 && same(active.get(0), binding);
    if (!same) {
      LocalDateTime from = binding.getValidFrom() == null ? now() : binding.getValidFrom();
      // 身份发生变化也以设备写入开始时间为界，覆盖回调先于本地提交到达的窗口。
      identities.close(staffId, from);
      DeviceIdentity item = new DeviceIdentity();
      item.setId(Ids.next()); item.setStaffId(staffId); item.setDeviceSn(binding.getDeviceSerialNumber());
      item.setFaceGroupId(binding.getCallbackGroup()); item.setIdentityType(binding.getIdentityType());
      item.setIdentityValue(binding.getIdentityValue()); item.setValidFrom(from);
      identities.insert(item);
    } else binding.setValidFrom(active.get(0).getValidFrom());
    events.wakeIdentity(binding.getDeviceSerialNumber(), binding.getCallbackGroup(),
        binding.getIdentityType(), binding.getIdentityValue(), now());
  }

  private boolean same(DeviceIdentity item, StaffDeviceBinding binding) {
    return Objects.equals(item.getDeviceSn(), binding.getDeviceSerialNumber())
        && Objects.equals(item.getFaceGroupId(), binding.getCallbackGroup())
        && Objects.equals(item.getIdentityType(), binding.getIdentityType())
        && Objects.equals(item.getIdentityValue(), binding.getIdentityValue());
  }

  /** 只读查询设备后一次事务建立基线；先查全、再写本地，不上传、不删除设备人员。 */
  public int baseline() {
    if (!properties.getRecognition().isProtocolVerified())
      throw new BusinessException("请先核实回调身份契约并配置 protocol-verified");
    ExhibitionProperties.Device target = client.target();
    Map<String, DeviceFaceClient.Person> people = client.people(target);
    List<StaffDeviceBinding> bindings = staff.bindings();
    LocalDateTime verified = now();
    for (StaffDeviceBinding binding : bindings) {
      if (!target.getSerialNumber().equals(binding.getDeviceSerialNumber())
          || !target.getFaceGroupId().equals(binding.getFaceGroupId()))
        throw new BusinessException("当前配置与已有绑定不一致，基线未更新");
      DeviceFaceClient.Person person = people.get(binding.getFaceUuid());
      if (person == null) throw new BusinessException("设备缺少已绑定人员，基线未更新");
      binding.setNumericGroupId(person.getGroupId()); binding.setDevicePersonId(person.getPersonId());
      binding.setDeviceUniqueId(person.getUniqueId()); binding.setModelState(person.getModelState());
      binding.setVerifiedAt(verified); binding.setValidFrom(verified);
      selectIdentity(binding);
      if (binding.getIdentityValue() == null) throw new BusinessException("设备未返回配置要求的身份，基线未更新");
    }
    Set<String> unique = new HashSet<>();
    for (StaffDeviceBinding binding : bindings)
      if (!unique.add(binding.getCallbackGroup() + "|" + binding.getIdentityType() + "|" + binding.getIdentityValue()))
        throw new BusinessException("人员回调身份重复，基线未更新");
    transaction.execute(status -> {
      for (StaffDeviceBinding binding : bindings) {
        maintain(binding, null, binding.getStaffId());
        staff.deleteBinding(binding.getStaffId()); staff.insertBinding(binding);
      }
      return null;
    });
    return bindings.size();
  }

  public Map<String, Object> bindingView(String staffId) {
    StaffDeviceBinding binding = staff.binding(staffId);
    if (binding == null) return null;
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("deviceSerialNumber", binding.getDeviceSerialNumber());
    result.put("faceGroupId", binding.getFaceGroupId()); result.put("numericGroupId", binding.getNumericGroupId());
    result.put("faceUuid", binding.getFaceUuid()); result.put("devicePersonId", binding.getDevicePersonId());
    result.put("deviceUniqueId", binding.getDeviceUniqueId()); result.put("identityType", binding.getIdentityType());
    result.put("identityValue", binding.getIdentityValue()); result.put("callbackGroup", binding.getCallbackGroup());
    result.put("modelState", binding.getModelState()); result.put("verifiedAt", offset(binding.getVerifiedAt()));
    result.put("validFrom", offset(binding.getValidFrom()));
    return result;
  }
}
