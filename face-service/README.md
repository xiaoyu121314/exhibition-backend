# 本机人脸特征服务

<!-- @author MoShangHuaKai -->

仅监听 `127.0.0.1:18090`，使用 OpenCV YuNet 检测、五点对齐和 SFace 提取128维单位向量。服务不保存图片、不维护人员库；Java 后台负责身份事务、重试和北京时间每日去重。代码默认关闭，当前现场已在数据库、模型验证和用户确认后启用；迁移到新环境时须先准备新表和模型。

Windows（Python 3.12及以上）：

```powershell
./scripts/setup-face-service.ps1 -PythonExecutable 'C:/Python314/python.exe'
./scripts/start-face-service.ps1
```

如需下载代理，安装脚本可传 `-ProxyUrl 'http://127.0.0.1:7890'`。模型来自 OpenCV 官方仓库，`models.json` 固定SHA-256；下载中断或摘要不符不会加载，许可原文随模型保存在 `models/LICENSE-*`。部署目录中也提供相同脚本和模型；虚拟环境不打包，需在目标机器重新安装。

Linux可执行：

```bash
python3 -m venv face-service/.venv
face-service/.venv/bin/python -m pip install -r face-service/requirements.txt
face-service/.venv/bin/python face-service/download_models.py
face-service/.venv/bin/python face-service/app.py --port 18090
```

接口为 `GET /health`、`POST /feature`。提取请求体是原始图片字节，最大1MiB；成功返回 `model` 和 `feature`。无脸、多脸、损坏图片返回422和固定错误码，服务失败返回500，不返回伪造特征。Java仅接受本机地址和已配置的模型版本，不将图片转发至代理或外部云服务。

服务使用CPU和单一推理锁；图片按实际五点对齐，不能把不同照片的MD5当作人员身份。默认余弦阈值0.363来自[OpenCV官方示例](https://docs.opencv.org/4.x/d0/dd4/tutorial_dnn_face.html)，不是现场准确率承诺，需用现场不同人员、光线和姿态样本校准。模糊、遮挡、极端侧脸可能无法提取有效特征；失败会通过后台统计状态和抓拍详情体现。

服务测试：`face-service/.venv/Scripts/python.exe -m unittest discover -s face-service/tests -v`。后端接入、SQL和前端字段映射见 [前端交接说明](../docs/monitor-frontend-handoff.md)。
