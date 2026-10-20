"""Fetch six fixed public sample photos; titles are display labels, never model inputs."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.request

root = Path(__file__).resolve().parents[1]
assets = root / "app/src/debug/assets"
folder = assets / "samples"
folder.mkdir(parents=True, exist_ok=True)
photos = [
    ("dog", "Dog in the sun", "photo-1552053831-71594a27632d"),
    ("cat", "A cat", "photo-1514888286974-6c03e2ca1dba"),
    ("beach", "Beach", "photo-1507525428034-b723cf961d3e"),
    ("mountain", "Mountains", "photo-1464822759023-fed622ff2c3b"),
    ("coffee", "Coffee", "photo-1509042239860-f550ce710b93"),
    ("bicycle", "Bicycle", "photo-1485965120184-e220f721d03e"),
]


def fetch(photo):
    name, title, photo_id = photo
    url = f"https://images.unsplash.com/{photo_id}?auto=format&fit=crop&w=800&q=85&fm=jpg"
    target = folder / f"{name}.jpg"
    if not target.is_file():
        request = urllib.request.Request(url, headers={"User-Agent": "GemmaPocketDemo/1.0"})
        with urllib.request.urlopen(request, timeout=60) as response:
            target.write_bytes(response.read())
    data = target.read_bytes()
    if not data.startswith(b"\xff\xd8"):
        raise RuntimeError(f"{name}: expected JPEG")
    print("SAMPLE", name, len(data), flush=True)
    return {"id": name, "title": title, "source": url, "license": "https://unsplash.com/license", "sha256": hashlib.sha256(data).hexdigest()}


with ThreadPoolExecutor(max_workers=4) as pool:
    samples = list(pool.map(fetch, photos))
(assets / "samples.json").write_text(json.dumps(samples, ensure_ascii=False, indent=2), encoding="utf-8")
