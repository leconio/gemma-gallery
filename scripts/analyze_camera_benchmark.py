"""Create the measured 100 full-resolution camera-photo report and raw CSV."""
import argparse
import csv
import hashlib
import html
import json
import math
from pathlib import Path
import statistics as stats

p = argparse.ArgumentParser()
p.add_argument('--directory', type=Path, required=True)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--receipt', type=Path, required=True)
args = p.parse_args()

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def dist(values):
    values = sorted(values); location = (len(values) - 1) * .95; lo, hi = math.floor(location), math.ceil(location)
    return dict(n=len(values), mean=stats.mean(values), median=stats.median(values),
                p95=values[lo] + (values[hi] - values[lo]) * (location - lo), minimum=min(values), maximum=max(values))

manifest = read(args.directory / 'manifest.json')
dataset = read(args.directory / 'dataset-info.json')
receipt = read(args.receipt)
reports = [read(path) for path in sorted(args.directory.glob('camera100-*.json'))]
assert len(reports) == 5
rows = []
for backend, threads in [('CPU', 1), ('CPU', 2), ('CPU', 4), ('CPU', 8), ('GPU', 4)]:
    matches = [r for r in reports if r['backend'] == backend and r['mainThreads'] == threads]
    assert len(matches) == 1
    r = matches[0]
    assert r['status'] == 'passed' and r['dataset'] == 'camera100'
    assert len(r['images']) == len({i['id'] for i in r['images']}) == r['sampleCount'] == 100
    assert {i['id'] for i in r['images']} == {i['id'] for i in manifest}
    warm_queries = [q for q in r['queries'] if q['round'] > 0]
    row = dict(label=f'CPU {threads}' if backend == 'CPU' else 'GPU', backend=backend, mainThreads=threads,
        runId=r['runId'], sampleCount=100, coldEngineInitMs=r['engineInitializationMs'],
        modelVerificationMs=r['modelVerificationMs'], firstImageMs=r['images'][0]['totalMs'],
        first100InstrumentedWallSeconds=r['firstPassImagesElapsedMs'] / 1000,
        first100PipelineSeconds=sum(i['totalMs'] for i in r['images']) / 1000,
        imageTotalMs=dist([i['totalMs'] for i in r['images']]),
        imagePreprocessMs=dist([i['preprocessMs'] for i in r['images']]),
        imageNativeInferenceMs=dist([i['inferenceMs'] for i in r['images']]),
        indexSaveMs=dist([i['saveMs'] for i in r['images']]),
        search100Ms=dist([q['totalMs'] for q in warm_queries]),
        searchNativeMs=dist([q['inferenceMs'] for q in warm_queries]),
        ranking100Ms=dist([q['rankMs'] for q in warm_queries]),
        firstSearchMs=r['queries'][0]['totalMs'],
        observedMaxPssMiB=max(m['pssKb'] for m in r['memory']) / 1024,
        sampledPeakRssMiB=r['peakSampledRssKb'] / 1024,
        processedCopiesBytes=sum(i['processedBytes'] for i in r['images']),
        indexBytes=r['indexBytes'], jsonBytesPerImage=r['indexBytes'] / 100,
        first20ImageMeanMs=stats.mean(i['totalMs'] for i in r['images'][:20]),
        last20ImageMeanMs=stats.mean(i['totalMs'] for i in r['images'][-20:]),
        startEnvironment=r['startEnvironment'], endEnvironment=r['endEnvironment'],
        maxObservedThermalStatus=max(m['environment']['thermalStatus'] for m in r['memory']))
    row['photosPerSecond'] = 100 / row['first100InstrumentedWallSeconds']
    rows.append(row)

calibration = [read(path) for path in (args.directory.parent / 'matrix').glob('matrix-*.json')]
summary = dict(dataset=dataset, configurations=rows, modelSha256=reports[0]['modelSha256'],
    modelBytes=reports[0]['modelBytes'], apkBytes=args.apk.stat().st_size,
    apkSha256=hashlib.sha256(args.apk.read_bytes()).hexdigest(), source=receipt['source'], buildRun=receipt['run_id'],
    counts=dict(imageInferences=sum(len(r['images']) for r in reports), textInferences=sum(len(r['queries']) for r in reports)),
    sixPhotoCalibration=dict(runs=len(calibration), correctQueries=sum(r['correctQueries'] for r in calibration),
                             totalQueries=sum(len(r['queries']) for r in calibration)),
    limitations=['One 100-photo process-cold run per backend/thread setting; no repeated cold-start confidence interval.',
                 'Thread count applies to the main encoder only; vision uses SDK defaults. Image jobs are serialized.',
                 '100 different real phone scenes, 12.2-13.1MP, same order for all settings; inputs are not upscaled.',
                 'Full-size JPEGs are official HDR+ outputs reprocessed from phone-camera raw bursts; not newly captured Galaxy S25 photos.',
                 'Device was charging. Runs wait for battery temperature <=38C and thermal status <=LIGHT before launch; temperatures are not held identical.',
                 'Network download/USB provisioning, MediaStore enumeration and app UI rendering are excluded.',
                 'PSS is observed at phase boundaries and every 10 photos; RSS sampled every 100ms. Not a continuous peak-PSS measurement.',
                 'The 100-photo search run is latency-only, without labeled semantic-relevance ground truth.',
                 'Debug APK with optimized upstream native library; no energy, background scheduler or full-library quality validation.'])
(args.directory / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
with (args.directory / 'measurements.csv').open('w', newline='', encoding='utf-8-sig') as output:
    writer = csv.DictWriter(output, fieldnames=['backend', 'mainThreads', 'runId', 'kind', 'round', 'id', 'query',
        'preprocessMs', 'readMs', 'inferenceMs', 'validationMs', 'saveMs', 'rankMs', 'totalMs', 'originalBytes', 'processedBytes'], extrasaction='ignore')
    writer.writeheader()
    for r in reports:
        for kind, measurements in [('image', r['images']), ('query', r['queries'])]:
            for measurement in measurements:
                writer.writerow(dict(measurement, backend=r['backend'], mainThreads=r['mainThreads'], runId=r['runId'], kind=kind))

lines = ['# Galaxy S25: 100 full-resolution phone photos', '',
    'Samsung SM-S931U · Qualcomm SM8750 · Android 16 / API 36. Measured on the connected physical phone.', '',
    f"Test set: **100 distinct scenes, {dataset['minMegapixels']:.2f}–{dataset['maxMegapixels']:.2f} MP, {dataset['totalBytes'] / 1e6:.2f} MB total**. Median JPEG {stats.median(i['bytes'] for i in manifest) / 1e6:.2f} MB; range {min(i['bytes'] for i in manifest) / 1e6:.2f}–{max(i['bytes'] for i in manifest) / 1e6:.2f} MB. Includes portrait and landscape images. Byte-identical public originals were transferred and each SHA256 was verified on the phone.", '',
    '[Google HDR+ dataset](https://hdrplusdata.org/dataset.html), results_20171023, 100 curated scenes selected deterministically (seed 20261019). These are full-resolution quality-95 JPEG outputs from official processing of phone-camera raw bursts. We did not resize or upscale the downloaded files. Attribution: Samuel W. Hasinoff et al., SIGGRAPH Asia 2016. [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Source URLs, dimensions and file hashes are in manifest.json.', '',
    '## Measured first scan and search', '',
    '| Backend / main threads | Model init ms | First image ms | All 100 photos s | Image p50 / p95 ms | Search p50 / p95 ms | Observed max PSS MiB |',
    '|---|---:|---:|---:|---:|---:|---:|']
for r in rows:
    lines.append(f"| {r['label']} | {r['coldEngineInitMs']:.0f} | {r['firstImageMs']:.0f} | {r['first100InstrumentedWallSeconds']:.2f} | {r['imageTotalMs']['median']:.0f} / {r['imageTotalMs']['p95']:.0f} | {r['search100Ms']['median']:.0f} / {r['search100Ms']['p95']:.0f} | {r['observedMaxPssMiB']:.0f} |")
lines += ['', 'All 100 images were newly imported and embedded in every run; cached vectors were never used for scan timing. The 100-photo wall time includes checkpoint/report overhead and periodic memory snapshots. Per-image timers exclude this instrumentation. Model init is separate from full-model SHA256 verification and image scanning. Model download/USB transfer are excluded.', '',
    '**CPU thread settings control the main encoder, not the entire vision pipeline.** LiteRT-LM Android 0.18.0 ignores visionBackend.threadCount; the vision encoder uses SDK defaults. The GPU also has CPU work/fallbacks. One image job is submitted at a time, matching the app and SDK serialization.', '',
    'Each photo follows the production importer: sampled JPEG decode (at most 2048px before final resize), EXIF orientation correction, max dimension 1024px, JPEG quality 90 private copy; then file read, native image embedding, normalization validation and atomic JSON index update. Original full-size input I/O and preprocessing are included. MediaStore enumeration and content-provider/Photos Picker UI overhead are not measured.', '',
    'Search has 24 warm timed queries per configuration (six queries repeated four times after the first round). It includes text embedding, validation, cosine similarity and sorting 100 indexed photos. The first query comes after image indexing; it is not a text-only engine-cold test. The 100-photo set has no labeled semantic-relevance judgments, so no retrieval accuracy is claimed for it.', '',
    '## Where the scan time goes', '',
    '| Backend | Preprocess p50 / p95 ms | Native image p50 / p95 ms | JSON save p50 / p95 ms | Rank 100 p50 ms |',
    '|---|---:|---:|---:|---:|']
for r in rows:
    lines.append(f"| {r['label']} | {r['imagePreprocessMs']['median']:.1f} / {r['imagePreprocessMs']['p95']:.1f} | {r['imageNativeInferenceMs']['median']:.1f} / {r['imageNativeInferenceMs']['p95']:.1f} | {r['indexSaveMs']['median']:.1f} / {r['indexSaveMs']['p95']:.1f} | {r['ranking100Ms']['median']:.3f} |")
lines += ['', '## Storage and memory', '',
    f"APK: {summary['apkBytes'] / 1e6:.2f} MB ({summary['apkBytes'] / 1024**2:.2f} MiB). Separate model: {summary['modelBytes'] / 1e6:.2f} MB ({summary['modelBytes'] / 1024**2:.2f} MiB).", '',
    'Each 768-dimensional Float32 vector is exactly **3,072 bytes**; 100 raw vectors use 307,200 bytes. The current JSON format stores decimal numbers and per-photo metadata, so its on-disk size is larger. Prepared private image copies are separate from vectors/index:', '',
    '| Backend | JSON index bytes | JSON bytes / photo | 100 prepared JPEG copies MB |', '|---|---:|---:|---:|']
for r in rows:
    lines.append(f"| {r['label']} | {r['indexBytes']:,} | {r['jsonBytesPerImage']:,.0f} | {r['processedCopiesBytes'] / 1e6:.2f} |")
lines += ['', 'The demo rewrites the full JSON index per photo. Replace this with incremental database writes and binary vectors before full-library use. At 10,000 photos, Float32 vectors alone use 30.72 MB, before metadata, database, thumbnails, objects, model and driver allocations.', '',
    'PSS includes Android-accounted GPU memory; RSS alone can understate GPU footprint. PSS is sampled at phase boundaries and every 10 photos; RSS every 100ms. Figures are observed samples, not a guaranteed continuous peak.', '',
    '## Temperature and sustained behavior', '',
    '| Backend | Start / end battery C | Max thermal status | First / last 20 image mean ms |', '|---|---:|---:|---:|']
for r in rows:
    lines.append(f"| {r['label']} | {r['startEnvironment']['batteryTemperatureC']:.1f} / {r['endEnvironment']['batteryTemperatureC']:.1f} | {r['maxObservedThermalStatus']} | {r['first20ImageMeanMs']:.0f} / {r['last20ImageMeanMs']:.0f} |")
lines += ['', 'Android thermal status: 0 NONE, 1 LIGHT, 2 MODERATE. Every run waits for battery temperature <=38C and status <=LIGHT before launch, but temperatures are not held identical. The device was charging and screen-on. Early/late image sets differ, so changes cannot be attributed solely to temperature. These runs do not establish energy use, all-day scanning, or background execution reliability.', '',
    '## Validation and reproducibility', '',
    f"Five process-cold runs, one per configuration, {summary['counts']['imageInferences']} image inferences and {summary['counts']['textInferences']} text inferences. Same 100-image order and same model/prefix/768 dimensions/256 max input length/70 vision tokens across runs. No reboot or OS page-cache flush. Fresh per-run native cache directory.", '',
    f"Separate six-public-photo calibration: {summary['sixPhotoCalibration']['correctQueries']}/{summary['sixPhotoCalibration']['totalQueries']} correct top-1 query results across {summary['sixPhotoCalibration']['runs']} earlier CPU/GPU runs. This is a small smoke test, not a general retrieval-quality result.", '',
    'The harness runs in foreground over the lock screen and keeps the screen on. Earlier background-frozen runs are retained under preflight/ and excluded. This does not test background scanning; the earlier lock-screen pause is a reason to validate a background scheduler separately.', '',
    f"APK SHA256: `{summary['apkSha256']}`. Build run: `{receipt['run_id']}`. Frozen source SHA256: `{receipt['source']['content_sha256']}`. Android assembleDebug/lintDebug and same-source shared JVM/downloader checks passed.", '',
    '[Official SDK thread setter](https://github.com/google-ai-edge/LiteRT-LM/blob/b2f686e2ed4718fb84ec398a61dd59ca0f0aff27/c/embedding_engine.cc#L154-L160). Raw measurements are in camera100-*.json and measurements.csv; summary.json contains all numeric aggregates. No personal photo-library files were accessed.', '']
markdown = '\n'.join(lines)
(args.directory / 'REPORT.md').write_text(markdown, encoding='utf-8')
table = []
for r in rows:
    values = [r['label'], f"{r['coldEngineInitMs']:.0f}", f"{r['first100InstrumentedWallSeconds']:.2f}",
        f"{r['imageTotalMs']['median']:.0f} / {r['imageTotalMs']['p95']:.0f}",
        f"{r['search100Ms']['median']:.0f} / {r['search100Ms']['p95']:.0f}", f"{r['observedMaxPssMiB']:.0f}"]
    table.append('<tr>' + ''.join('<td>' + html.escape(v) + '</td>' for v in values) + '</tr>')
document = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Galaxy S25: 100 camera photos</title><style>body{font:16px/1.6 system-ui;max-width:1160px;margin:40px auto;padding:0 24px;color:#17212a;background:#fafbfc}table{width:100%;border-collapse:collapse;background:white}th,td{padding:14px 10px;border-bottom:1px solid #dce2e8;text-align:right;font-size:14px}th:first-child,td:first-child{text-align:left}th{background:#e6edf4}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.7 system-ui;background:white;padding:22px;border:1px solid #dce2e8}a{color:#005bbb}.scroll{overflow:auto}</style><h1>Galaxy S25 · 100 camera photos</h1>'''
document += f"<p>100 distinct full-resolution scenes · {dataset['minMegapixels']:.1f}–{dataset['maxMegapixels']:.1f} MP · {dataset['totalBytes'] / 1e6:.0f} MB originals · physical Samsung SM-S931U</p>"
document += '<p>Scan includes original JPEG import, EXIF, resizing, model inference and JSON writes. CPU thread count applies to the main encoder; vision uses SDK defaults.</p><div class="scroll"><table><thead><tr><th>Backend</th><th>Cold init ms</th><th>100 photos s</th><th>Image p50/p95 ms</th><th>Search p50/p95 ms</th><th>Observed PSS MiB</th></tr></thead><tbody>' + ''.join(table) + '</tbody></table></div>'
document += '<p><a href="measurements.csv">Raw CSV</a> · <a href="summary.json">Summary JSON</a> · <a href="manifest.json">Image provenance</a> · <a href="REPORT.md">Full report</a></p><pre>' + html.escape(markdown) + '</pre></html>'
(args.directory / 'report.html').write_text(document, encoding='utf-8')
print(json.dumps(dict(configurations=rows, counts=summary['counts']), indent=2))
