#!/usr/bin/env python3
"""Evaluate real query embeddings against the dedicated simulator's cached photo vectors.

This is an opt-in development tool. It never changes the photo index or sends photos
to a service. Run on Air through project-dev after building the simulator app.
"""
import argparse
import json
import math
from pathlib import Path
import shutil
import sqlite3
import struct
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = "com.spawnlau.gemmapocket.ios"
QUERIES = [
    "a dog", "a cat", "a cup of coffee", "mountains", "a bicycle", "a beach",
    "dog", "cat", "coffee", "mountain landscape", "bike", "seaside",
    "animals", "pets", "landscape", "flowers", "a waterfall",
    "a car", "a computer", "a pizza", "a document", "a train", "a smartphone",
    "an airplane", "a birthday cake", "a horse", "an elephant", "a giraffe",
    "狗", "猫", "咖啡", "山", "自行车", "海滩", "宠物",
]
VALIDATION_QUERIES = [
    "a puppy", "a kitten", "a black and white dog", "a dog playing outside", "a cat sitting down",
    "a hot drink", "a coffee mug", "a red bicycle", "a bicycle on the street", "snowy mountains",
    "an ocean beach", "a sandy shore", "a pet", "cats and dogs", "natural scenery",
    "a laptop", "a bus", "a motorcycle", "a truck", "a boat", "a tiger", "a lion",
    "a bowl of noodles", "a receipt", "a selfie", "小狗", "小猫", "一杯咖啡", "雪山", "海边",
    "公路自行车", "汽车", "电脑", "披萨", "火车", "手机", "飞机", "大象",
]


def sim(*args):
    return subprocess.check_output(["xcrun", "simctl", *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--udid", required=True)
    parser.add_argument("--queries", type=Path)
    parser.add_argument("--validation", action="store_true")
    args = parser.parse_args()
    devices = json.loads(sim("list", "devices", "available", "-j"))["devices"]
    device = next(d for group in devices.values() for d in group if d["udid"] == args.udid)
    if not device["name"].startswith("GemmaPocket-"):
        raise SystemExit("Select an isolated GemmaPocket simulator")
    if device["state"] != "Booted":
        sim("boot", args.udid)
        sim("bootstatus", args.udid, "-b")
    app = ROOT / "iosApp/build/simulator/Build/Products/Debug-iphonesimulator/GemmaPocket.app"
    sim("install", args.udid, str(app))
    directory = Path(sim("get_app_container", args.udid, BUNDLE, "data")) / "Library/Application Support/GemmaPocket"
    sim("privacy", args.udid, "grant", "photos", BUNDLE)
    # Restore only missing public fixtures, preserving all existing simulator photos.
    audit = directory / "system-photo-audit.json"
    existing = {p["title"] for p in json.loads(audit.read_text())["photos"]} if audit.exists() else set()
    fixtures = ROOT / "iosApp/build/test-fixtures"
    fixtures.mkdir(parents=True, exist_ok=True)
    for source in (ROOT / "app/src/debug/assets/samples").glob("*.jpg"):
        name = "GemmaFixture-" + source.name
        if name not in existing:
            target = fixtures / name
            shutil.copyfile(source, target)
            sim("addmedia", args.udid, str(target))
    queries = json.loads(args.queries.read_text()) if args.queries else VALIDATION_QUERIES if args.validation else QUERIES
    (directory / "search-audit-queries.json").write_text(json.dumps(queries, ensure_ascii=False))
    output = directory / "search-score-audit.json"
    output.unlink(missing_ok=True)
    subprocess.run(["xcrun", "simctl", "terminate", args.udid, BUNDLE], capture_output=True)
    launched = time.time()
    sim("launch", args.udid, BUNDLE, "--photo-audit", "--search-score-audit")
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if output.exists() and audit.exists() and audit.stat().st_mtime >= launched:
            native = json.loads(audit.read_text())
            connection = sqlite3.connect(directory / "system-photo-index.sqlite")
            rows = connection.execute("SELECT asset_id,vector FROM photo_index WHERE vector IS NOT NULL").fetchall()
            connection.close()
            if len(rows) == len(native["photos"]):
                break
        time.sleep(1)
    else:
        raise SystemExit("Timed out waiting for real model embeddings and the fixture index")
    photos = {p["id"]: p["title"] for p in native["photos"]}
    vectors = {key: struct.unpack("<768f", vector) for key, vector in rows if key in photos}
    measured = json.loads(output.read_text())
    cutoff = measured.get("minimumSimilarity")
    result = {"model": measured["model"], "minimumSimilarity": cutoff, "photoCount": len(vectors), "queries": []}
    for record in measured["queries"]:
        query = record["vector"]
        scores = []
        for key, vector in vectors.items():
            score = sum(a*b for a, b in zip(query, vector)) / math.sqrt(sum(a*a for a in query)*sum(b*b for b in vector))
            scores.append({"photo": photos[key], "score": score, "included": score >= cutoff if cutoff is not None else None})
        result["queries"].append({"query": record["query"], "scores": sorted(scores, key=lambda s: -s["score"])})
    destination = ROOT / "iosApp/build/search-score-evaluation.json"
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({"result": str(destination), "photos": len(vectors), "queries": len(result["queries"])}))
    for row in result["queries"]:
        print(row["query"], " | ".join(f"{r['photo']} {r['score']:.4f}" for r in row["scores"][:3]))


if __name__ == "__main__":
    main()
