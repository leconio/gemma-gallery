"""Compile and exercise the real downloader on the configured JDK, without Android or model weights."""
from pathlib import Path
import json
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/java/com/spawnlau/gemmapocket/ModelDownloader.java"
test = root / "tests/com/spawnlau/gemmapocket/ModelDownloaderTest.java"
spec = json.loads((root / "model-manifest.json").read_text())
code = source.read_text()
assert f'FILENAME="{spec["filename"]}"' in code
assert f'SHA256="{spec["sha256"]}"' in code
assert f'BYTES={spec["size"]}L' in code
assert f'/resolve/{spec["revision"]}/' in code
print("Pinned download metadata matches model-manifest.json", flush=True)
with tempfile.TemporaryDirectory(prefix="gemma-downloader-") as folder:
    subprocess.run(["javac", "--release", "17", "-d", folder, str(source), str(test)], check=True)
    subprocess.run(["java", "-Djava.io.tmpdir=" + folder, "-cp", folder, "com.spawnlau.gemmapocket.ModelDownloaderTest"], check=True)
