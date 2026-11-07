-- 展厅后台汇总建表脚本：包含当前版本全部7张业务表及设备人脸比对字段，适用MySQL 5.7.44。
-- @author MoShangHuaKai
-- 使用方式：先选择已创建的业务数据库，再执行本文件即可初始化完整结构，无需追加recognition-upgrade.sql。
-- 本文件只包含CREATE TABLE，不自动创建/切换数据库，不删除数据，不执行ALTER或历史识别回填。
-- IF NOT EXISTS只跳过已有表，不更新已有结构；调整前的旧结构升级使用独立的一次性recognition-upgrade.sql。
-- 结构约定：InnoDB、utf8mb4_unicode_ci；主键使用UUID字符串，业务编号按文本保存。
-- 时间约定：DATETIME(3)按Asia/Shanghai北京时间记录，精度为毫秒，字段本身不保存时区。
-- 表间关系由应用事务维护，以下均为逻辑关联，不声明物理外键。
-- 人数来源：exh_capture_event中有效FaceReco按hall_id、business_date、staff_id日去重，不建立每日汇总计数表。
-- 表清单：exh_media、exh_staff、exh_device、exh_staff_device、exh_device_identity、exh_capture_event、exh_staff_import_task。

-- 1. 媒体文件元信息
-- 对应Models.Media；先定义媒体表，工作人员头像和抓拍事件通过媒体ID引用。
CREATE TABLE IF NOT EXISTS exh_media (
    id VARCHAR(36) PRIMARY KEY COMMENT '媒体文件主键，应用生成的UUID字符串；图片接口按该ID定位文件，不向调用方暴露真实磁盘路径',
    relative_path VARCHAR(255) NOT NULL COMMENT '文件相对于exhibition.storage-directory的存储路径，包含用途目录、日期目录和文件名；不是HTTP地址或绝对盘符路径',
    content_type VARCHAR(128) NOT NULL COMMENT '文件MIME类型；图片保存为image/jpeg，导入源文件为xlsx对应的application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    owner_type VARCHAR(20) NOT NULL COMMENT '文件用途：STAFF登记照、CAPTURE人脸抓拍、BACKGROUND背景图、RECOGNIZE匹配库图、IMPORT导入Excel原文件；不是用户角色',
    created_at DATETIME(3) NOT NULL COMMENT '文件保存及元数据登记时间；与业务引用一起用于判断无引用文件是否满足延迟清理条件'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件存储元数据表；保存登记照、抓拍、背景、匹配库图和导入源文件的路径及用途，文件内容保存在服务器存储目录';

-- 2. 工作人员档案
-- 对应Models.Staff；单头像档案，工号唯一，删除为逻辑删除。
CREATE TABLE IF NOT EXISTS exh_staff (
    id VARCHAR(36) PRIMARY KEY COMMENT '工作人员档案主键，应用生成的UUID字符串；供设备人员身份关联，不是登录账号',
    staff_no VARCHAR(64) NOT NULL COMMENT '工作人员工号，后台从001起按数值递增，至少三位，文本保存；修改保持不变，Excel按工号更新，逻辑删除后仍占用原工号',
    name VARCHAR(32) NOT NULL COMMENT '工作人员姓名，与设备最大32字符限制一致',
    sex VARCHAR(6) NOT NULL COMMENT '性别：male男、female女；与设备接口取值一致',
    position VARCHAR(100) NOT NULL COMMENT '工作人员职位或岗位名称，用于大屏展示；直接保存名称，不关联岗位字典表',
    department VARCHAR(100) NOT NULL DEFAULT '' COMMENT '所属部门名称，允许空字符串；Excel更新时部门留空会清空原部门，不是部门ID',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '人员启用标记：1启用、0停用；只有启用且未删除的档案同步至实体设备',
    deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT '逻辑删除标记：0正常、1已删除；删除后移除设备人员，保留档案及工号占用',
    avatar_id VARCHAR(36) COMMENT '工作人员唯一头像媒体ID，逻辑关联exh_media.id；新增时必须提供有效图片',
    version INT NOT NULL DEFAULT 0 COMMENT '人员档案乐观锁版本号，默认0；人员资料、照片或删除操作变更时递增，更新请求携带旧版本防止并发覆盖',
    created_at DATETIME(3) NOT NULL COMMENT '工作人员档案创建时间，由数据库写入；不是首次被摄像头识别的时间',
    updated_at DATETIME(3) NOT NULL COMMENT '工作人员资料、照片关联或删除标记最近一次更新的数据库时间，毫秒精度',
    UNIQUE KEY uk_staff_no(staff_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作人员业务档案表；维护工号、姓名、职位和照片关联，用于人员维护及设备同步，不保存人员登录账号密码';

-- 3. 目标设备状态
-- 对应EventMapper；心跳与首次识别启用时间持久化，管理地址、凭据和识别契约仍由外部YAML维护。
CREATE TABLE IF NOT EXISTS exh_device (
    serial_number VARCHAR(128) PRIMARY KEY COMMENT '已通过配置校验的设备序列号，来自deviceInfo.serialNumber；用于心跳更新与抓拍来源关联，不在本表保存设备密钥',
    recognition_started_at DATETIME(3) COMMENT '工作人员识别链路首次启用的北京时间，启用前可为NULL；应用重启不重置，用于统计起点',
    last_heartbeat DATETIME(3) NULL COMMENT '后台最近收到该设备合法心跳的服务器时间，不使用摄像头事件时间；应用结合心跳间隔判断在线状态'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='摄像头状态表；保存最后心跳与识别首次启用时间，设备管理凭据及识别契约由外部配置维护';

-- 4. 工作人员当前设备绑定
-- 对应Models.StaffDeviceBinding；设备同步及回读成功后，与工作人员档案、身份历史在同一事务提交。
CREATE TABLE IF NOT EXISTS exh_staff_device (
    staff_id VARCHAR(36) PRIMARY KEY COMMENT '后台工作人员ID，逻辑关联exh_staff.id',
    device_serial_number VARCHAR(128) NOT NULL COMMENT '目标设备序列号，避免修改配置后误操作另一台设备',
    face_group_id VARCHAR(64) NOT NULL COMMENT '设备脸库GroupID2，不是查询时使用的数字GroupID',
    face_uuid VARCHAR(64) NOT NULL COMMENT '设备回读确认的FaceUUID，更新和删除使用此身份；不直接假定等于回调personId或uniqueId',
    numeric_group_id VARCHAR(64) COMMENT '设备人员列表所属数字GroupID，与用于配置定位的GroupID2分开保存',
    device_person_id VARCHAR(128) COMMENT '设备人员列表PersonID原值；仅在协议核实并选为identity-source后参与回调身份关联',
    device_unique_id VARCHAR(128) COMMENT '设备人员列表UniqueID原值，设备未提供时可为NULL；不与FaceUUID混用',
    model_state VARCHAR(64) COMMENT '设备回读ModelState原值；上传成功或本字段为空均不能表示建模成功',
    identity_type VARCHAR(32) COMMENT '已验证且唯一选定的回调身份字段：personId或uniqueId',
    identity_value VARCHAR(128) COMMENT '从已验证identity-source选出的实际身份值；禁止按姓名、工号或图片名兜底',
    callback_group VARCHAR(128) COMMENT '已验证的回调工作人员库作用域值，不直接假定等于GroupID2或数字GroupID',
    verified_at DATETIME(3) COMMENT '最近一次设备人员身份及建模状态回读核实时间，北京时间',
    valid_from DATETIME(3) COMMENT '当前身份区间起点：设备写入开始时间或首次只读基线核实时间；相同身份更新沿用旧起点',
    UNIQUE KEY uk_device_face(device_serial_number,face_uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作人员与设备脸库人员的身份关联';

-- 5. 工作人员设备身份历史
-- 对应Models.DeviceIdentity；按事件发生时间匹配左闭右开区间[valid_from,valid_until)，支持停用、删除后的迟到补传。
CREATE TABLE IF NOT EXISTS exh_device_identity (
    id VARCHAR(36) PRIMARY KEY COMMENT '设备身份历史区间主键，应用生成的UUID字符串',
    staff_id VARCHAR(36) NOT NULL COMMENT '本地工作人员ID，逻辑关联exh_staff.id；查询历史不按当前停用或逻辑删除状态过滤',
    device_sn VARCHAR(128) NOT NULL COMMENT '该身份所属设备序列号，与当前绑定device_serial_number保持同一来源',
    face_group_id VARCHAR(128) NOT NULL COMMENT '已验证的回调工作人员库作用域，与identity_type、identity_value共同确定身份',
    identity_type VARCHAR(32) NOT NULL COMMENT '唯一选定的回调身份字段：personId或uniqueId；不轮流尝试多个字段',
    identity_value VARCHAR(128) NOT NULL COMMENT '协议验证后选定的实际设备身份值，不使用姓名、工号或图片文件名',
    valid_from DATETIME(3) NOT NULL COMMENT '身份有效起点，左闭；新身份为设备写入开始时间，首次基线为核实时间，不推测更早归属',
    valid_until DATETIME(3) COMMENT '身份有效终点，右开；设备确认移除后关闭，NULL表示仍有效；关闭历史至少覆盖允许补传窗口',
    KEY idx_identity_lookup(device_sn,face_group_id,identity_type,identity_value,valid_from),
    KEY idx_identity_staff(staff_id,valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作人员设备身份历史';

-- 6. 抓拍及设备比对事件
-- 对应Models.Capture；一条记录是一条去重后的协议事件；同一人FaceSnapshot与FaceReco分别保存。
CREATE TABLE IF NOT EXISTS exh_capture_event (
    id VARCHAR(36) PRIMARY KEY COMMENT '后台抓拍事件记录主键，应用生成的UUID字符串；用于抓拍记录查询',
    event_key VARCHAR(64) NOT NULL COMMENT '事件幂等SHA-256：设备、类型、编号、时间、trackId、原图SHA-256；FaceReco还包含固定顺序候选身份，唯一索引拦截重传',
    device_sn VARCHAR(128) NOT NULL COMMENT '抓拍来源设备序列号，来自deviceInfo.serialNumber并须匹配外部设备配置；心跳状态可按exh_device.serial_number查询',
    event_id VARCHAR(64) NOT NULL COMMENT '摄像头协议info.eventId，按文本保存；设备重启后可能复用，不能单独作为全局唯一键',
    track_id VARCHAR(64) COMMENT '摄像头协议info.trackId，仅标记图片内的人脸，缺省写空字符串；不是跨抓拍的永久身份，不能用于全天人员去重',
    event_time DATETIME(3) NOT NULL COMMENT '摄像头事件实际发生时间，由info.time转换为北京时间后保存；迟到补传仍保留原始发生日期',
    received_at DATETIME(3) NOT NULL COMMENT '后台接收并登记事件的服务器时间；与event_time分开保存，用于记录排序及保留期限判断',
    business_date DATE NOT NULL COMMENT '事件业务日期，取北京时间event_time的日期部分；用于按日期查询抓拍记录',
    image_id VARCHAR(36) COMMENT '人脸抓拍图片媒体ID，逻辑关联exh_media.id且用途为CAPTURE；接收时已过期而未保存图片的记录可为NULL',
    background_id VARCHAR(36) COMMENT '背景图片媒体ID，逻辑关联exh_media.id且用途为BACKGROUND；设备未提供背景图或事件已过期时可为NULL',
    status VARCHAR(20) NOT NULL COMMENT '接收状态：RECEIVED已保存、EXPIRED超出保留期限不保存图片',
    hall_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '事件接收时的exhibition.hall-id快照；工作人员日去重按该展厅和business_date查询',
    event_type VARCHAR(32) NOT NULL DEFAULT 'FaceSnapshot' COMMENT '设备事件类型：FaceSnapshot普通抓拍、FaceReco设备比对；同一eventId的两种事件不能合并',
    match_status VARCHAR(32) NOT NULL DEFAULT 'NOT_COMPARED' COMMENT '比对状态：NOT_COMPARED未比对、STAFF有效工作人员、NO_MATCH未匹配、UNRESOLVED身份未关联、REVIEW待确认；独立于接收status',
    match_reason VARCHAR(255) COMMENT '比对分类的具体原因，供管理详情排查；不使用姓名推断身份',
    match_number INT COMMENT '设备info.matchNumber候选数量，0表示明确未匹配；不是人数，多候选不直接判为STAFF',
    similarity DECIMAL(9,6) COMMENT '设备候选匹配分数0至100，保留6位小数；有效STAFF须达到已核实阈值，分数不是概率或准确率',
    device_person_id VARCHAR(128) COMMENT '设备回调personInfo.personId原值，与人员列表身份的对应关系须单独验证',
    device_unique_id VARCHAR(128) COMMENT '设备回调personInfo.uniqueId原值，不能未经验证认定等于FaceUUID',
    callback_group VARCHAR(128) COMMENT '从配置选定group-field读取的工作人员库作用域，须与已核实group-value一致',
    identity_type VARCHAR(32) COMMENT '本事件关联所选回调身份字段：personId或uniqueId',
    identity_value VARCHAR(128) COMMENT '本事件关联所选设备身份值；结合设备、库、字段及发生时间查询身份历史',
    staff_id VARCHAR(36) COMMENT '有效关联的本地工作人员ID，逻辑关联exh_staff.id；STAFF日人数使用COUNT(DISTINCT staff_id)',
    staff_no VARCHAR(64) COMMENT '事件首次有效关联时的工作人员工号快照，档案后续修改不回写',
    staff_name VARCHAR(32) COMMENT '事件首次有效关联时的姓名快照，不用作设备身份关联条件',
    staff_position VARCHAR(100) COMMENT '事件首次有效关联时的职位快照，供大屏直接展示',
    staff_department VARCHAR(100) COMMENT '事件首次有效关联时的部门快照，供大屏直接展示',
    staff_avatar_id VARCHAR(36) COMMENT '首次有效关联时的登记照媒体ID，逻辑关联exh_media.id；换头像或删除档案后仍随事件保留',
    recognize_image_id VARCHAR(36) COMMENT '设备本次recognizeImage匹配库图媒体ID，用途RECOGNIZE；设备未返回或事件过期时可为NULL',
    picture_md5 VARCHAR(32) COMMENT '收到的原始抓拍解码字节MD5，供校验和ACK使用；不得从存储后重编码JPEG反推',
    protocol_json MEDIUMTEXT COMMENT '递归移除picture Base64后的原始协议JSON，保留完整候选和质量姿态等诊断字段，不保存人脸向量模型',
    ack_json TEXT COMMENT '协议验收后生成的完整ACK JSON快照，重传复用；尚未验收时为NULL，不能伪造成功ACK',
    resolve_attempts INT NOT NULL DEFAULT 0 COMMENT '未关联事件已执行的自动重试次数，最多3次；对应身份提交后可重置并唤醒，人工重试只处理UNRESOLVED',
    next_resolve_at DATETIME(3) COMMENT '持久化的下次本地身份关联时间；无待执行任务时为NULL，服务重启后继续扫描',
    processed_at DATETIME(3) COMMENT '事件保存或重新关联的最近处理时间，北京时间；用于statisticsUpdatedAt，不替代事件发生时间',
    KEY idx_recognition_count(hall_id,business_date,event_type,match_status,status,staff_id),
    KEY idx_resolve(match_status,next_resolve_at),
    UNIQUE KEY uk_capture_event(event_key),
    KEY idx_capture_status(status,received_at),
    KEY idx_capture_date(business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='摄像头抓拍事件表；保存协议来源、图片及幂等键，持久化后应答设备，保存设备比对身份与人员快照，按staffId计算日去重';

-- 7. 工作人员Excel导入任务
-- 对应Models.ImportTask；源文件、进度和恢复状态持久化，成功结果与工作人员事务一并提交。
CREATE TABLE IF NOT EXISTS exh_staff_import_task (
    id VARCHAR(36) PRIMARY KEY COMMENT '人员Excel导入任务主键，应用生成的UUID字符串；上传接口返回该ID，供前端查询进度及最终结果',
    file_id VARCHAR(36) NOT NULL COMMENT '上传Excel原文件的媒体ID，逻辑关联exh_media.id且用途为IMPORT；供后台解析及重启恢复，不通过图片接口下载',
    status VARCHAR(20) NOT NULL COMMENT '任务状态：PENDING待处理、PROCESSING处理中、SUCCESS整批提交成功、FAILED校验或提交失败；失败不保留部分人员更新',
    phase VARCHAR(30) NOT NULL COMMENT '执行阶段：UPLOADED上传、VALIDATING校验、SYNCING_DEVICE同步、COMMITTING提交、COMPENSATING补偿、COMPLETED完成、ROLLED_BACK回滚、RECOVERED恢复、RECONCILIATION_REQUIRED需人工核对',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '文件解析通过后的有效人员数据行数，忽略表头及完全空白行；尚未完成解析时为0，不是工作表最大行号',
    processed_rows INT NOT NULL DEFAULT 0 COMMENT '已执行资料或图片校验的人员行数，包含检查失败的行，不等于成功导入数；重启后整批重新校验时归零',
    created_count INT NOT NULL DEFAULT 0 COMMENT '整批事务成功提交的新增工号记录数；校验中或任务失败时为0，不记录回滚前的临时新增数',
    updated_count INT NOT NULL DEFAULT 0 COMMENT '整批事务成功提交的已有工号更新记录数；按更新行计数，不要求字段实际变化；校验中或失败时为0',
    errors TEXT COMMENT '错误明细JSON数组，元素包含row和message；row为Excel可见行号，0表示文件解析或整批提交阶段错误；无错误时为[]',
    created_at DATETIME(3) NOT NULL COMMENT '导入任务创建的数据库时间，对应后台接受并持久化上传文件后的登记时间',
    updated_at DATETIME(3) NOT NULL COMMENT '任务状态或进度最近一次持久化的数据库时间；已结束任务按该时间判断结果及源文件引用的清理期限',
    KEY idx_import_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作人员Excel导入任务表；保存异步执行阶段、行进度、整批提交结果及错误明细，支持重启后从源文件重新校验';

-- 本机特征服务的当日身份与处理记录；已有库只需执行 face-feature-upgrade.sql。
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

