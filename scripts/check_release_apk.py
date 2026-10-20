"""Check the actual release artifact for notices and accidental debug/model assets."""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def check(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        forbidden = [name for name in names if name.startswith("assets/samples/")
                     or name == "assets/samples.json" or name.endswith((".litertlm", ".gguf"))]
        if forbidden:
            raise ValueError("Release contains debug photos or model weights: " + ", ".join(forbidden))
        for name in ("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md"):
            if apk.read("assets/licenses/" + name) != (ROOT / name).read_bytes():
                raise ValueError("Missing or altered license notice: " + name)
        for name in names:
            if name.startswith("classes") and name.endswith(".dex") and b"BenchmarkActivity" in apk.read(name):
                raise ValueError("Debug benchmark activity found in release code")
    print("RELEASE_CHECK_PASSED: notices included; no debug photo library, benchmark activity, or model weights")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    check(parser.parse_args().apk)
