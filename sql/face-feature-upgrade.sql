-- 当天匿名身份和异步特征任务；只新增两张表，不改工作人员、抓拍或设备身份数据。
-- @author MoShangHuaKai
CREATE TABLE IF NOT EXISTS exh_person_daily (
    id VARCHAR(36) PRIMARY KEY COMMENT '当天身份UUID，不向前端公开特征向量',
    hall_id VARCHAR(64) NOT NULL,
    business_date DATE NOT NULL COMMENT '设备事件发生的北京时间日期，跨日重新去重',
    model_id VARCHAR(64) NOT NULL COMMENT '模型版本隔离，不比较不同模型的向量',
    staff_id VARCHAR(36) NULL COMMENT '设备已确认的工作人员ID；NULL表示尚未确认为工作人员的当日身份',
    features_json MEDIUMTEXT NOT NULL COMMENT '最多5个128维单位向量；只在服务端读取',
    merged_into VARCHAR(36) NULL COMMENT '匿名身份后续确认为同一工作人员时的归并目标，保留原记录用于追溯',
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_person_daily_staff(hall_id,business_date,model_id,staff_id),
    KEY idx_person_daily(hall_id,business_date,model_id,merged_into)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS exh_face_feature_event (
    capture_id VARCHAR(36) PRIMARY KEY COMMENT '抓拍ID，一次事实事件只形成一次特征处理结果',
    model_id VARCHAR(64) NOT NULL,
    person_id VARCHAR(36) NULL COMMENT '该次抓拍关联的当日身份，可通过merged_into追溯最终身份',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING、READY、FAILED；失败不当作新访客累加',
    error VARCHAR(128) NULL COMMENT '固定错误信息，不保存模型路径、图片或特征响应',
    attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NULL,
    processed_at DATETIME(3) NOT NULL,
    KEY idx_face_feature_due(status,next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
