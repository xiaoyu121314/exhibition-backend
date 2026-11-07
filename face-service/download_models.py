"""下载官方模型并校验固定摘要；已校验文件直接复用。@author MoShangHuaKai"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy", default="")
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    manifest = json.loads((directory / "models.json").read_text(encoding="utf-8"))
    target = directory / "models"
    target.mkdir(exist_ok=True)
    handlers = [urllib.request.ProxyHandler({"http": args.proxy, "https": args.proxy})] if args.proxy else []
    opener = urllib.request.build_opener(*handlers)
    for model in manifest["models"]:
        filename = target / model["filename"]
        if not filename.exists() or hashlib.sha256(filename.read_bytes()).hexdigest() != model["sha256"]:
            # 先下载到临时文件，通过摘要后再替换，网络中断不会把半个模型当成可用文件。
            temporary = filename.with_suffix(".download")
            with opener.open(model["url"], timeout=60) as response, temporary.open("wb") as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != model["sha256"]:
                temporary.unlink()
                raise RuntimeError("模型摘要不匹配：" + model["filename"])
            temporary.replace(filename)
        license_file = target / model["licenseFile"]
        if not license_file.exists():
            with opener.open(model["license"], timeout=30) as response:
                license_file.write_bytes(response.read())
        print(model["filename"] + " verified", flush=True)


if __name__ == "__main__":
    main()
