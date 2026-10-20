"""Download a small, independent-photo smoke cohort. Names carry no semantic labels."""
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / ".cache" / "lab-fixtures"

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT / "tests/fixtures/lab-photos.json").read_text(encoding="utf-8-sig"))
    def download(entry):
        target = OUT / entry["file"]
        if not target.exists():
            local = ROOT / "app/src/debug/assets/samples" / (entry["category"] + ".jpg")
            if entry["role"] == "reference" and local.exists():
                shutil.copyfile(local, target)
            else:
                with urllib.request.urlopen(entry["source"], timeout=60) as response:
                    target.write_bytes(response.read())
        assert hashlib.sha256(target.read_bytes()).hexdigest() == entry["sha256"], f"Fixture checksum mismatch: {target.name}"
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(download, manifest["fixtures"]))
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(manifest['fixtures'])} pinned photos in {OUT}")

if __name__ == "__main__":
    main()
