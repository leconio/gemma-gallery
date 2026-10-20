"""Download the pinned public model and verify its complete SHA-256."""
from pathlib import Path
import hashlib
import json
import time
import urllib.request

root = Path(__file__).resolve().parents[1]
spec = json.loads((root / "model-manifest.json").read_text(encoding="utf-8"))
folder = root / "models"
folder.mkdir(exist_ok=True)
target = folder / spec["filename"]


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


if target.exists() and target.stat().st_size == spec["size"] and digest(target) == spec["sha256"]:
    print("MODEL_VERIFIED", target, flush=True)
else:
    part = target.with_suffix(".part")
    url = f'https://huggingface.co/{spec["repository"]}/resolve/{spec["revision"]}/{spec["filename"]}'
    request = urllib.request.Request(url, headers={"User-Agent": "EmbeddingGemmaLocalDemo/1.0"})
    start = time.monotonic()
    previous = start
    count = 0
    with urllib.request.urlopen(request, timeout=90) as response, part.open("wb") as output:
        while chunk := response.read(2 * 1024 * 1024):
            output.write(chunk)
            count += len(chunk)
            now = time.monotonic()
            if now - previous >= 5:
                print(f"MODEL_DOWNLOAD {count}/{spec['size']} bytes {count/(now-start)/1e6:.1f} MB/s", flush=True)
                previous = now
    if count != spec["size"] or digest(part) != spec["sha256"]:
        raise RuntimeError("Model size or SHA-256 mismatch; the partial file has been preserved.")
    part.replace(target)
    print("MODEL_VERIFIED", target, spec["sha256"], flush=True)
