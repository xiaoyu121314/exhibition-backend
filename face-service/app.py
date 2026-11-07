"""本机人脸检测、五点对齐和 SFace 特征提取；不保存照片、不决定人员身份。

Java 使用返回的单位向量完成北京时间当日去重，设备比对仍负责工作人员身份确认。
@author MoShangHuaKai
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading

# 在 OpenCV 初始化前限制解码像素，避免小体积压缩图申请过大的解码内存。
os.environ.setdefault("OPENCV_IO_MAX_IMAGE_PIXELS", "16000000")
import cv2
import numpy as np

DIRECTORY = Path(__file__).resolve().parent
MAX_IMAGE_BYTES = 1024 * 1024


class FeatureError(ValueError):
    """可预期的输入问题，返回固定代码，不把模型路径或图片内容写入响应。"""


class FeatureEngine:
    def __init__(self, models_directory=None):
        manifest = json.loads((DIRECTORY / "models.json").read_text(encoding="utf-8"))
        directory = Path(models_directory) if models_directory else DIRECTORY / "models"
        paths = []
        for item in manifest["models"]:
            filename = directory / item["filename"]
            if not filename.is_file() or hashlib.sha256(filename.read_bytes()).hexdigest() != item["sha256"]:
                raise RuntimeError("模型缺失或摘要不符，请先执行 download_models.py")
            paths.append(str(filename))
        self.model_id = manifest["modelId"]
        self.lock = threading.Lock()
        cv2.setNumThreads(1)
        self.detector = cv2.FaceDetectorYN.create(paths[0], "", (320, 320), 0.8, 0.3, 5000)
        self.recognizer = cv2.FaceRecognizerSF.create(paths[1], "")

    def extract(self, content):
        if not content or len(content) > MAX_IMAGE_BYTES:
            raise FeatureError("INVALID_IMAGE_SIZE")
        try:
            image = cv2.imdecode(np.frombuffer(content, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None or min(image.shape[:2]) < 20:
                raise FeatureError("INVALID_IMAGE")
            # OpenCV 的两个推理对象会维护输入状态，同一进程中串行使用，避免并发请求相互覆盖。
            with self.lock:
                self.detector.setInputSize((image.shape[1], image.shape[0]))
                _, faces = self.detector.detect(image)
                if faces is None or len(faces) == 0:
                    raise FeatureError("NO_FACE")
                if len(faces) != 1:
                    raise FeatureError("MULTIPLE_FACES")
                aligned = self.recognizer.alignCrop(image, faces[0])
                feature = self.recognizer.feature(aligned).reshape(-1).astype(np.float64)
        except cv2.error as error:
            raise FeatureError("INVALID_IMAGE") from error
        norm = float(np.linalg.norm(feature))
        if feature.size != 128 or not np.isfinite(feature).all() or not np.isfinite(norm) or norm <= 0:
            raise FeatureError("INVALID_FEATURE")
        return {"model": self.model_id, "feature": (feature / norm).tolist()}


def make_handler(engine):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def reply(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != "/health":
                self.reply(404, {"error": "NOT_FOUND"})
                return
            self.reply(200, {"ready": True, "model": engine.model_id, "dimensions": 128})

        def do_POST(self):
            if self.path != "/feature":
                self.reply(404, {"error": "NOT_FOUND"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if self.headers.get("Transfer-Encoding") or not 0 < length <= MAX_IMAGE_BYTES:
                    self.reply(413, {"error": "INVALID_IMAGE_SIZE"})
                    return
                content = self.rfile.read(length)
                if len(content) != length:
                    raise FeatureError("INCOMPLETE_IMAGE")
                self.reply(200, engine.extract(content))
            except (FeatureError, ValueError) as error:
                self.reply(422, {"error": str(error) if isinstance(error, FeatureError) else "INVALID_LENGTH"})
            except (TimeoutError, ConnectionError):
                self.close_connection = True
            except Exception:
                self.reply(500, {"error": "INFERENCE_FAILED"})

        def log_message(self, format, *args):
            # 访问日志不记录图片、向量或请求体；Java 负责记录任务状态与可重试错误。
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18090)
    parser.add_argument("--models-directory")
    args = parser.parse_args()
    engine = FeatureEngine(args.models_directory)
    # 特征接口仅供本机 Java 后台调用，不向摄像头或前端暴露特征向量。
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(engine))
    print("Face feature service ready: 127.0.0.1:%s / %s" % (args.port, engine.model_id), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
