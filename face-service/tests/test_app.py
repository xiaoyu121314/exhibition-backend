"""本机HTTP协议、图像边界及真实模型空图验证。@author MoShangHuaKai"""
import http.client
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import FeatureEngine, FeatureError, make_handler, ThreadingHTTPServer
import cv2
import numpy as np


class FakeEngine:
    model_id = "test-model"

    def extract(self, content):
        if content == b"no-face":
            raise FeatureError("NO_FACE")
        return {"model": self.model_id, "feature": [1.0] + [0.0] * 127}


class HttpContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(FakeEngine()))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body=body)
            response = connection.getresponse()
            payload = response.read()
            self.assertEqual(int(response.getheader("Content-Length")), len(payload))
            return response.status, json.loads(payload)
        finally:
            connection.close()

    def test_health_is_ready_without_exposing_paths_or_vectors(self):
        status, body = self.request("GET", "/health")
        self.assertEqual((status, body), (200, {"ready": True, "model": "test-model", "dimensions": 128}))

    def test_feature_returns_explicit_model_and_dimensions(self):
        status, body = self.request("POST", "/feature", b"test-image")
        self.assertEqual(status, 200)
        self.assertEqual(len(body["feature"]), 128)

    def test_no_face_is_rejected_instead_of_returning_fake_feature(self):
        self.assertEqual(self.request("POST", "/feature", b"no-face"), (422, {"error": "NO_FACE"}))

    def test_empty_image_and_unknown_route_are_rejected(self):
        self.assertEqual(self.request("POST", "/feature", b"")[0], 413)
        self.assertEqual(self.request("GET", "/missing")[0], 404)


@unittest.skipUnless((Path(__file__).resolve().parents[1] / "models/face_recognition_sface_2021dec.onnx").is_file(), "官方模型未下载")
class RealModelBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = FeatureEngine()

    def test_blank_image_does_not_count_as_a_person(self):
        success, image = cv2.imencode(".jpg", np.zeros((64, 64, 3), dtype=np.uint8))
        self.assertTrue(success)
        with self.assertRaisesRegex(FeatureError, "NO_FACE"):
            self.engine.extract(image.tobytes())

    def test_corrupt_and_oversized_images_are_rejected(self):
        with self.assertRaisesRegex(FeatureError, "INVALID_IMAGE"):
            self.engine.extract(b"invalid-image")
        with self.assertRaisesRegex(FeatureError, "INVALID_IMAGE_SIZE"):
            self.engine.extract(b"x" * (1024 * 1024 + 1))


if __name__ == "__main__":
    unittest.main()
