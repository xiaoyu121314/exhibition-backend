package com.moshanghuakai.exhibition.persistence;

import com.moshanghuakai.exhibition.domain.Models.ImportTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 导入任务与结果持久化；成功标记与人员批量更新在同一事务中提交。 @author MoShangHuaKai */
public interface ImportMapper {
  @Insert(
      "INSERT INTO exh_staff_import_task(id,file_id,status,phase,total_rows,processed_rows,created_count,updated_count,errors,created_at,updated_at) VALUES(#{id},#{fileId},#{status},#{phase},0,0,0,0,'[]',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))")
  void insert(ImportTask task);

  @Select("SELECT * FROM exh_staff_import_task WHERE id=#{id}")
  ImportTask find(String id);

  @Select("SELECT * FROM exh_staff_import_task WHERE status='PENDING' ORDER BY created_at LIMIT 1")
  List<ImportTask> pending();

  @Update(
      "UPDATE exh_staff_import_task SET status='PROCESSING',phase='VALIDATING',updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='PENDING'")
  int claim(String id);

  @Update(
      "UPDATE exh_staff_import_task SET status=#{status},phase=#{phase},total_rows=#{totalRows},processed_rows=#{processedRows},created_count=#{createdCount},updated_count=#{updatedCount},errors=#{errors},updated_at=CURRENT_TIMESTAMP(3) WHERE id=#{id}")
  void update(ImportTask task);

  // 只有尚未执行设备动作的校验阶段可以安全恢复；中断的设备操作禁止自动重发。
  @Update("UPDATE exh_staff_import_task SET status=CASE WHEN phase='VALIDATING' THEN 'PENDING' ELSE 'FAILED' END,errors=CASE WHEN phase='VALIDATING' THEN errors ELSE '[{\"row\":0,\"message\":\"导入在设备同步或提交期间中断，设备状态需人工核对，禁止自动重试\"}]' END,phase=CASE WHEN phase='VALIDATING' THEN 'RECOVERED' ELSE 'RECONCILIATION_REQUIRED' END,updated_at=CURRENT_TIMESTAMP(3) WHERE status='PROCESSING'")
  void recover();

  @Delete(
      "DELETE FROM exh_staff_import_task WHERE updated_at < #{before} AND status IN ('SUCCESS','FAILED')")
  void purge(LocalDateTime before);
}
