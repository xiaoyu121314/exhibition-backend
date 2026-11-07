-- @author MoShangHuaKai
-- 对调整前结构仅执行一次，先备份并停止服务；不自动运行，不删除档案或脸库。
-- 旧抓拍保持FaceSnapshot/NOT_COMPARED，不补算历史。
ALTER TABLE exh_staff_device
    ADD COLUMN numeric_group_id VARCHAR(64) COMMENT '数字脸库编号',
    ADD COLUMN device_person_id VARCHAR(128) COMMENT '人员列表PersonID',
    ADD COLUMN device_unique_id VARCHAR(128) COMMENT '人员列表UniqueID',
    ADD COLUMN model_state VARCHAR(64) COMMENT '建模状态原值，空值不表示成功',
    ADD COLUMN identity_type VARCHAR(32) COMMENT '验证的回调字段personId或uniqueId',
    ADD COLUMN identity_value VARCHAR(128) COMMENT '明确选择的身份值，不以姓名兜底',
    ADD COLUMN callback_group VARCHAR(128) COMMENT '验证的回调脸库作用域',
    ADD COLUMN verified_at DATETIME(3) COMMENT '设备回查时间',
    ADD COLUMN valid_from DATETIME(3) COMMENT '身份有效起点';
ALTER TABLE exh_capture_event
    ADD COLUMN hall_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '接收时的展厅标识',
    ADD COLUMN event_type VARCHAR(32) NOT NULL DEFAULT 'FaceSnapshot' COMMENT 'FaceSnapshot或FaceReco',
    ADD COLUMN match_status VARCHAR(32) NOT NULL DEFAULT 'NOT_COMPARED' COMMENT 'NOT_COMPARED、STAFF、NO_MATCH、UNRESOLVED、REVIEW',
    ADD COLUMN match_reason VARCHAR(255) COMMENT '分类原因',
    ADD COLUMN match_number INT COMMENT '候选数量，不是人数',
    ADD COLUMN similarity DECIMAL(9,6) COMMENT '设备分数0至100，不是概率',
    ADD COLUMN device_person_id VARCHAR(128) COMMENT '回调personId',
    ADD COLUMN device_unique_id VARCHAR(128) COMMENT '回调uniqueId',
    ADD COLUMN callback_group VARCHAR(128) COMMENT '回调脸库值',
    ADD COLUMN identity_type VARCHAR(32) COMMENT '选定回调字段',
    ADD COLUMN identity_value VARCHAR(128) COMMENT '选定身份值',
    ADD COLUMN staff_id VARCHAR(36) COMMENT '本地工作人员ID',
    ADD COLUMN staff_no VARCHAR(64) COMMENT '工号快照',
    ADD COLUMN staff_name VARCHAR(32) COMMENT '姓名快照',
    ADD COLUMN staff_position VARCHAR(100) COMMENT '职位快照',
    ADD COLUMN staff_department VARCHAR(100) COMMENT '部门快照',
    ADD COLUMN staff_avatar_id VARCHAR(36) COMMENT '登记照快照引用',
    ADD COLUMN recognize_image_id VARCHAR(36) COMMENT '本次匹配库图媒体ID',
    ADD COLUMN picture_md5 VARCHAR(32) COMMENT '原始字节MD5，供ACK使用',
    ADD COLUMN protocol_json MEDIUMTEXT COMMENT '删除picture载荷的协议结构',
    ADD COLUMN ack_json TEXT COMMENT '验证后的ACK快照，未验收为空',
    ADD COLUMN resolve_attempts INT NOT NULL DEFAULT 0 COMMENT '自动重试次数最多3次',
    ADD COLUMN next_resolve_at DATETIME(3) COMMENT '下次关联时间',
    ADD COLUMN processed_at DATETIME(3) COMMENT '最近处理时间',
    ADD KEY idx_recognition_count(hall_id,business_date,event_type,match_status,status,staff_id),
    ADD KEY idx_resolve(match_status,next_resolve_at);
ALTER TABLE exh_device ADD COLUMN recognition_started_at DATETIME(3), MODIFY COLUMN last_heartbeat DATETIME(3) NULL;
ALTER TABLE exh_media MODIFY COLUMN owner_type VARCHAR(20) NOT NULL COMMENT '文件用途：STAFF登记照、CAPTURE人脸抓拍、BACKGROUND背景图、RECOGNIZE匹配库图、IMPORT导入Excel原文件；不是用户角色';
-- 有效区间为左闭右开，按事件发生时间匹配。
CREATE TABLE IF NOT EXISTS exh_device_identity (
    id VARCHAR(36) PRIMARY KEY COMMENT '身份区间UUID',
    staff_id VARCHAR(36) NOT NULL COMMENT '工作人员ID',
    device_sn VARCHAR(128) NOT NULL COMMENT '来源设备',
    face_group_id VARCHAR(128) NOT NULL COMMENT '回调脸库作用域',
    identity_type VARCHAR(32) NOT NULL COMMENT 'personId或uniqueId',
    identity_value VARCHAR(128) NOT NULL COMMENT '验证后的身份值',
    valid_from DATETIME(3) NOT NULL COMMENT '设备写入起点或基线时间',
    valid_until DATETIME(3) COMMENT '设备移除时间，NULL为有效',
    KEY idx_identity_lookup(device_sn,face_group_id,identity_type,identity_value,valid_from),
    KEY idx_identity_staff(staff_id,valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作人员设备身份历史';
