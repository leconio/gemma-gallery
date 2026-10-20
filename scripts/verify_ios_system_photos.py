#!/usr/bin/env python3
"""Real PhotoKit + native-model UI acceptance on an explicitly selected Gemma test simulator."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import time
import shutil

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = "com.spawnlau.gemmapocket.ios"

def run(argv, **kwargs):
    return subprocess.run(argv, check=True, text=True, **kwargs)

def container(udid):
    return Path(subprocess.check_output(["xcrun", "simctl", "get_app_container", udid, BUNDLE, "data"], text=True).strip())

def audit(udid):
    root = container(udid) / "Library/Application Support/GemmaPocket"
    photo = json.loads((root / "system-photo-audit.json").read_text())
    with sqlite3.connect(f"file:{root / 'system-photo-index.sqlite'}?mode=ro", uri=True) as db:
        rows = db.execute("SELECT asset_id, revision, length(vector), read_ms, embed_ms, error FROM photo_index ORDER BY asset_id").fetchall()
    return photo, rows

def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--udid", required=True); args = parser.parse_args()
    devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "available", "--json"], text=True))
    device = next((d for group in devices["devices"].values() for d in group if d["udid"] == args.udid), None)
    if not device or not device["name"].startswith("GemmaPocket-"):
        raise SystemExit("Use an explicitly selected GemmaPocket test simulator; personal/other-project simulators are refused")
    if device["state"] != "Booted":
        run(["xcrun", "simctl", "boot", args.udid]); run(["xcrun", "simctl", "bootstatus", args.udid, "-b"])
    run(["python3", "scripts/build_ios.py"], cwd=ROOT)
    app = ROOT / "iosApp/build/simulator/Build/Products/Debug-iphonesimulator/GemmaPocket.app"
    run(["xcrun", "simctl", "install", args.udid, str(app)])
    # Preflight reads the actual library to add only missing, uniquely named test fixtures.
    # The UI workflow resets this grant and must exercise the normal OS permission dialog.
    run(["xcrun", "simctl", "privacy", args.udid, "grant", "photos", BUNDLE])
    subprocess.run(["xcrun", "simctl", "terminate", args.udid, BUNDLE], capture_output=True)
    launched = time.time()
    run(["xcrun", "simctl", "launch", args.udid, BUNDLE, "--photo-audit"])
    audit_file = container(args.udid) / "Library/Application Support/GemmaPocket/system-photo-audit.json"
    for _ in range(60):
        current = json.loads(audit_file.read_text()) if audit_file.exists() else {}
        if current.get("access") == "full" and audit_file.stat().st_mtime >= launched: break
        time.sleep(0.5)
    else: raise RuntimeError("PhotoKit preflight did not finish")
    present = {p["title"] for p in current["photos"]}
    fixture_folder = ROOT / "iosApp/build/test-fixtures"; fixture_folder.mkdir(parents=True, exist_ok=True)
    for name in ["dog", "cat", "beach", "coffee", "mountain", "bicycle"]:
        title = f"GemmaFixture-{name}.jpg"
        if title not in present:
            target = fixture_folder / title
            shutil.copy2(ROOT / f"app/src/debug/assets/samples/{name}.jpg", target)
            run(["xcrun", "simctl", "addmedia", args.udid, str(target)])
    # Fixtures are real PhotoKit assets; inference never reads app-bundled samples.
    run(["xcrun", "simctl", "privacy", args.udid, "reset", "photos", BUNDLE])
    run(["python3", "scripts/build_ios.py", "--test", args.udid, "--skip-framework", "--only-test", "GemmaPocketUITests/GemmaPocketUITests/testSystemLibraryWorkflow"], cwd=ROOT)
    photos, rows = audit(args.udid)
    assert photos["access"] == "full"
    dog = next(p for p in photos["photos"] if p["title"] == "GemmaFixture-dog.jpg")
    assert not any(p["title"] == "GemmaFixture-beach.jpg" for p in photos["photos"]), "System asset was not deleted"
    album_ids = {a["id"] for a in photos["albums"] if a["title"] == "Gemma Test Album"}
    assert album_ids.intersection(dog["albums"]), "PhotoKit did not add the photo to the real album"
    assert {r[0] for r in rows} == {p["id"] for p in photos["photos"]}, "SQLite and system photo IDs differ"
    assert all(r[2] == 3072 and not r[5] for r in rows), "Some real images lack valid embeddings"
    result = {"udid": args.udid, "device": device["name"], "fullAccess": True, "photoCount": len(rows),
        "nativeInference": True, "systemAlbumVerified": True, "systemDeletionVerified": True,
        "cancelledDeletionVerified": True, "binaryVectorBytes": 3072,
        "rowDigest": hashlib.sha256(json.dumps(rows).encode()).hexdigest(),
        "sourceSha256": __import__("os").environ.get("DEVFLOW_CONTENT_SHA256")}
    # This denies access using the simulator's supported permission control, without deleting media.
    run(["xcrun", "simctl", "privacy", args.udid, "revoke", "photos", BUNDLE])
    run(["python3", "scripts/build_ios.py", "--test", args.udid, "--skip-framework", "--only-test", "GemmaPocketUITests/GemmaPocketUITests/testDeniedAccessHidesLibrary"], cwd=ROOT)
    denied, denied_rows = audit(args.udid)
    assert denied["access"] == "denied" and not denied["photos"] and not denied_rows
    result["revokedAccessClearsIndex"] = True
    run(["xcrun", "simctl", "privacy", args.udid, "grant", "photos", BUNDLE])
    subprocess.run(["xcrun", "simctl", "terminate", args.udid, BUNDLE], capture_output=True)
    run(["xcrun", "simctl", "launch", args.udid, BUNDLE, "--photo-audit"])
    for _ in range(240):
        restored, restored_rows = audit(args.udid)
        if restored["access"] == "full" and len(restored_rows) == len(restored["photos"]) and all(r[2] == 3072 for r in restored_rows): break
        time.sleep(0.5)
    else: raise RuntimeError("Restored Photos access did not rebuild the index")
    result["restoredPhotoCount"] = len(restored_rows)
    folder = ROOT / "iosApp/build"; folder.mkdir(parents=True, exist_ok=True)
    (folder / "system-photos-acceptance.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))

if __name__ == "__main__": main()
