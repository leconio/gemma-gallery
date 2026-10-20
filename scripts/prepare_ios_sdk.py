"""Prepare the official Swift wrapper and iOS binary without cloning model history.

Every source blob is pinned to the release commit; the binary uses Google's SHA-256.
Only Package.swift is local, connecting those unchanged sources to the binary slice.
"""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def fetch(url, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".partial")
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as out:
        if not response.url.startswith("https://"):
            raise ValueError("SDK downloads require HTTPS")
        while data := response.read(1024 * 1024): out.write(data)
    temporary.replace(destination)

def prepare():
    lock = json.loads((ROOT / "iosApp/sdk-lock.json").read_text(encoding="utf-8-sig"))
    build = ROOT / "iosApp/build"
    cache = build / "sdk-downloads"; cache.mkdir(parents=True, exist_ok=True)
    package = build / ("LiteRTLM-" + lock["version"]); package.mkdir(parents=True, exist_ok=True)
    archive = cache / ("litert-swift-" + lock["version"] + ".tar")
    for entry in lock["files"]:
        file = package / entry["path"]
        def valid(data): return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == entry["gitBlob"]
        if file.is_file() and valid(file.read_bytes()): continue
        file.parent.mkdir(parents=True, exist_ok=True)
        if archive.is_file():
            with tarfile.open(archive) as tar: data = tar.extractfile(entry["path"]).read()
            if not valid(data): raise ValueError("SDK source checksum mismatch: " + entry["path"])
            file.write_bytes(data)
        else:
            fetch("https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/" + lock["commit"] + "/" + entry["path"], file)
        if not valid(file.read_bytes()): raise ValueError("SDK source checksum mismatch: " + entry["path"])
    binary = cache / "CLiteRTLM.xcframework.zip"
    expected = lock["binary"]["sha256"]
    if not binary.is_file(): fetch(lock["binary"]["url"], binary)
    with binary.open("rb") as f:
        digest = hashlib.sha256()
        while data := f.read(1024 * 1024): digest.update(data)
        if digest.hexdigest() != expected: raise ValueError("SDK binary checksum mismatch")
    framework = package / "CLiteRTLM.xcframework"
    if not framework.exists():
        with zipfile.ZipFile(binary) as archive:
            for info in archive.infolist():
                target = (package / info.filename).resolve()
                if not target.is_relative_to(package.resolve()): raise ValueError("Invalid SDK archive path")
            archive.extractall(package)
    manifest = '''// swift-tools-version: 5.9
// Generated adapter for unchanged official LiteRT-LM release files; see sdk-lock.json.
import PackageDescription
let package = Package(name: "LiteRTLM", platforms: [.iOS(.v16)],
  products: [.library(name: "LiteRTLM", targets: ["LiteRTLM"])],
  targets: [.binaryTarget(name: "CLiteRTLM", path: "CLiteRTLM.xcframework"),
            .target(name: "LiteRTLM", dependencies: ["CLiteRTLM"], path: "swift")])
'''
    (package / "Package.swift").write_text(manifest)
    (package / "provenance.json").write_text(json.dumps(lock, indent=2))
    print("Verified official LiteRT-LM " + lock["version"] + " at " + lock["commit"])
    return package

if __name__ == "__main__": prepare()
