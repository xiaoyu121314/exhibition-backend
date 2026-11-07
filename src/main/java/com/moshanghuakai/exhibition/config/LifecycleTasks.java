package com.moshanghuakai.exhibition.config;

import com.moshanghuakai.exhibition.common.StorageService;
import com.moshanghuakai.exhibition.domain.Models.Media;
import com.moshanghuakai.exhibition.persistence.*;
import com.moshanghuakai.exhibition.staff.StaffService;
import java.time.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 单实例任务恢复及过期清理；先删除到期引用，再清理无引用文件，文件删除失败留待下轮重试。 @author MoShangHuaKai */
@Slf4j
@Component
public class LifecycleTasks {
  private final EventMapper events;
  private final ImportMapper imports;
  private final MediaMapper media;
  private final StorageService storage;
  private final ExhibitionProperties properties;
  private final TransactionTemplate transaction;
  private final StaffService staff;
  private final IdentityMapper identities;
  private final FaceFeatureMapper faceFeatures;

  public LifecycleTasks(
      EventMapper events,
      ImportMapper imports,
      MediaMapper media,
      StorageService storage,
      ExhibitionProperties properties,
      TransactionTemplate transaction,
      StaffService staff, IdentityMapper identities, FaceFeatureMapper faceFeatures) {
    this.events = events;
    this.imports = imports;
    this.media = media;
    this.storage = storage;
    this.properties = properties;
    this.transaction = transaction;
    this.staff = staff;
    this.identities = identities;
    this.faceFeatures = faceFeatures;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    imports.recover();
  }

  @Scheduled(cron = "0 15 3 * * *", zone = "Asia/Shanghai")
  public void cleanup() {
    // 上传的未绑定图片在设备同步期间尚无档案引用，清理也必须遵守人员维护锁。
    staff.withMaintenance(() -> {
      cleanupLocked();
      return null;
    });
  }

  private void cleanupLocked() {
    LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai")),
        before = now.minusDays(properties.getRetentionDays());
    transaction.execute(
        status -> {
          // 与原抓拍共用保留期：先移除特征任务，再清理匿名当日身份和到期抓拍。
          faceFeatures.purgeEvents(before);
          faceFeatures.purgePeople(before);
          events.purge(before);
          identities.purge(before);
          imports.purge(now.minusDays(1));
          return null;
        });
    // 旧主照片、回滚后遗留元信息及已删除人员照片，只有在所有业务引用解除后才能删除。
    for (int page = 0; page < 20; page++) {
      java.util.List<Media> candidates = media.unused(now.minusDays(1));
      if (candidates.isEmpty()) break;
      for (Media item : candidates) storage.delete(item);
    }
    storage.cleanupOrphanFiles(now.minusDays(1));
  }
}
