"""Download 100 distinct, full-resolution phone-camera JPEGs from Google's HDR+ dataset."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import pathlib
import random
import struct
import time
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)

def fetch(url):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=90) as response:
                return response.read()
        except Exception:
            if attempt == 3: raise
            time.sleep(2 ** (attempt + 1))

def jpeg_size(data):
    assert data[:2] == b'\xff\xd8', 'Not a JPEG'
    offset = 2
    while offset < len(data):
        assert data[offset] == 255
        while data[offset] == 255: offset += 1
        marker = data[offset]; offset += 1
        if marker in [1] + list(range(0xD0, 0xD9)): continue
        length = struct.unpack('>H', data[offset:offset + 2])[0]
        if marker in [0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF]:
            height, width = struct.unpack('>HH', data[offset + 3:offset + 7]); return width, height
        offset += length
    raise ValueError('JPEG dimensions not found')

listing_url = ('https://storage.googleapis.com/storage/v1/b/hdrplusdata/o?' + urllib.parse.urlencode(dict(
    prefix='20171106_subset/', matchGlob='**/final.jpg', maxResults=1000, fields='items(name,size),nextPageToken')))
listing = json.loads(fetch(listing_url))
assert not listing.get('nextPageToken')
latest = sorted({i['name'].split('/')[1] for i in listing['items']})[-1]
candidates = [i for i in listing['items'] if i['name'].split('/')[1] == latest]
random.Random(20261019).shuffle(candidates)
assert len({i['name'].split('/')[-2] for i in candidates}) == len(candidates)
print(json.dumps({'candidateScenes': len(candidates), 'version': latest}), flush=True)
cache = args.output.parent / 'hdrplus-candidates'
cache.mkdir(exist_ok=True)

def download(item):
    url = 'https://storage.googleapis.com/hdrplusdata/' + item['name']
    path = cache / (item['name'].split('/')[-2] + '.jpg')
    data = path.read_bytes() if path.exists() else fetch(url)
    assert len(data) == int(item['size'])
    width, height = jpeg_size(data)
    if not (11_500_000 <= width * height <= 60_000_000) or min(width, height) < 2600 or max(width, height) < 3800:
        print(json.dumps({'excludedScene': path.stem, 'width': width, 'height': height}), flush=True)
        return None
    if not path.exists(): path.write_bytes(data)
    return dict(sourceScene=path.stem, url=url, sourcePage='https://hdrplusdata.org/dataset.html',
                width=width, height=height, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                license='CC BY-SA 4.0', licenseUrl='https://creativecommons.org/licenses/by-sa/4.0/',
                attribution='Samuel W. Hasinoff et al., HDR+ Burst Photography Dataset, SIGGRAPH Asia 2016', local=path)

selected = []
with ThreadPoolExecutor(max_workers=4) as pool:
    for offset in range(0, len(candidates), 8):
        for item in pool.map(download, candidates[offset:offset + 8]):
            if item is not None and len(selected) < 100: selected.append(item)
        print(json.dumps({'qualified': len(selected), 'inspected': min(offset + 8, len(candidates))}), flush=True)
        if len(selected) == 100: break
assert len(selected) == 100, f'Only {len(selected)} large original scenes qualified'
manifest = []
for index, item in enumerate(selected, 1):
    item = dict(item)
    local = item.pop('local')
    item.update(id=f'camera-{index:03}', title=f'Camera test photo {index:03}', filename=f'camera-{index:03}.jpg')
    destination = args.output / item['filename']
    if not destination.exists(): destination.write_bytes(local.read_bytes())
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == item['sha256']
    manifest.append(item)
assert len({i['sha256'] for i in manifest}) == 100
(args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
info = dict(name='HDR+ phone camera originals 100', source='https://hdrplusdata.org/dataset.html',
            version=latest, seed=20261019, count=100, totalBytes=sum(i['bytes'] for i in manifest),
            minMegapixels=min(i['width'] * i['height'] / 1e6 for i in manifest),
            maxMegapixels=max(i['width'] * i['height'] / 1e6 for i in manifest),
            criteria='100 distinct curated scenes, original final.jpg, 11.5-60 MP, short edge >=2600, long edge >=3800',
            transformations='None. Original quality-95 JPEG upload bytes retained.',
            attribution='Samuel W. Hasinoff et al., Burst photography for high dynamic range and low-light imaging on mobile cameras, SIGGRAPH Asia 2016',
            license='CC BY-SA 4.0', licenseUrl='https://creativecommons.org/licenses/by-sa/4.0/')
(args.output / 'dataset-info.json').write_text(json.dumps(info, indent=2), encoding='utf-8')
print(json.dumps(info), flush=True)
