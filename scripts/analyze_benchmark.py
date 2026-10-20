"""Summarize measured Android reports. Never substitute estimates for missing runs."""
import argparse
import csv
import html
import json
import math
import pathlib
import statistics

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=pathlib.Path, required=True)
parser.add_argument('--apk', type=pathlib.Path, required=True)
parser.add_argument('--receipt', type=pathlib.Path, required=True)
args = parser.parse_args()

def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def percentile(values, fraction):
    values = sorted(values)
    position = (len(values) - 1) * fraction
    lower, upper = math.floor(position), math.ceil(position)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)

def distribution(values):
    assert values
    return dict(n=len(values), median=statistics.median(values), p95=percentile(values, .95),
                mean=statistics.mean(values), minimum=min(values), maximum=max(values))

reports = [read(path) for path in sorted((args.root / 'matrix').glob('matrix-*.json'))]
assert len(reports) == 10, f'Expected 10 balanced runs, got {len(reports)}'
assert all(r['status'] == 'passed' for r in reports)
assert len({(r['modelSha256'], r['sdk'], r['dimensions']) for r in reports}) == 1
rows = []
for backend, threads in [('CPU', 1), ('CPU', 2), ('CPU', 4), ('CPU', 8), ('GPU', 4)]:
    group = [r for r in reports if r['backend'] == backend and r['mainThreads'] == threads]
    assert len(group) == 2
    # Every configuration has the same two initial six-photo passes. Thermal
    # guards can omit later rounds; compare photos 2-6 from those first passes.
    warm_images = [i for r in group for i in [x for x in r['images'] if x['round'] == 0][1:]]
    warm_queries = [q for r in group for q in r['queries'] if q['round'] > 0]
    row = dict(label=f'CPU {threads}' if backend == 'CPU' else 'GPU', backend=backend, mainThreads=threads,
        runs=[r['runId'] for r in group], coldInitMs=distribution([r['engineInitializationMs'] for r in group]),
        shaVerificationMs=distribution([r['modelVerificationMs'] for r in group]),
        firstImageMs=distribution([r['images'][0]['totalMs'] for r in group]),
        firstSixPipelineMs=distribution([sum(i['totalMs'] for i in r['images'] if i['round'] == 0) for r in group]),
        firstSixInstrumentedElapsedMs=distribution([r['firstSixImagesElapsedMs'] for r in group]),
        warmImageMs=distribution([i['totalMs'] for i in warm_images]),
        warmNativeImageMs=distribution([i['inferenceMs'] for i in warm_images]),
        warmImageSaveMs=distribution([i['saveMs'] for i in warm_images]),
        warmQueryMs=distribution([q['totalMs'] for q in warm_queries]),
        firstQueryMs=distribution([r['queries'][0]['totalMs'] for r in group]),
        warmRankSixMs=distribution([q['rankMs'] for q in warm_queries]),
        observedMaxPssMiB=max(m['pssKb'] for r in group for m in r['memory']) / 1024,
        sampledPeakRssMiB=max(r['peakSampledRssKb'] for r in group) / 1024,
        correctQueries=sum(r['correctQueries'] for r in group), totalQueries=sum(len(r['queries']) for r in group),
        sixImageIndexBytes=distribution([r['sixImageIndexBytes'] for r in group]),
        batteryTemperatureRangeC=[min(r['startEnvironment']['batteryTemperatureC'] for r in group),
                                  max(r['endEnvironment']['batteryTemperatureC'] for r in group)],
        maxObservedThermalStatus=max(m['environment']['thermalStatus'] for r in group for m in r['memory']))
    row['warmImagesPerSecond'] = 1000 / row['warmImageMs']['mean']
    row['estimated1000ImagesSeconds'] = row['warmImageMs']['mean']
    rows.append(row)

receipt = read(args.receipt)
summary = dict(device=read(args.root / 'matrix/device.json'), configurations=rows,
    modelSha256=reports[0]['modelSha256'], modelBytes=reports[0]['modelBytes'], apkBytes=args.apk.stat().st_size,
    source=receipt['source'], buildRun=receipt['run_id'],
    counts=dict(runs=len(reports), imageInferences=sum(len(r['images']) for r in reports),
                textInferences=sum(len(r['queries']) for r in reports)),
    scope='Six bundled public JPEG photos, 800px wide; cold app process and fresh runtime cache directories. '
          'Model provisioned over USB and SHA-256 checked; network download excluded. Warm metrics omit first round. '
          '1/2/4/8 controls the main encoder only; vision uses SDK defaults. One image job at a time. '
          'Charging during measurement; not an energy test. Debug application and production native SDK.',
    scale=[], additionalRuns=[])
for folder in ['scale', 'sustained', 'cache']:
    for path in sorted((args.root / folder).glob('*.json')) if (args.root / folder).exists() else []:
        r = read(path)
        if r.get('status') != 'passed':
            continue
        extra = dict(run=r['runId'], folder=folder, backend=r['backend'], mainThreads=r['mainThreads'],
                     initMs=r['engineInitializationMs'], imageCount=len(r['images']),
                     startEnvironment=r['startEnvironment'], endEnvironment=r['endEnvironment'],
                     freshRuntimeCacheDirectory=r['freshRuntimeCacheDirectory'])
        image_values = [i['totalMs'] for i in r['images'] if i['round'] > 0]
        if image_values:
            extra.update(warmImageMs=distribution(image_values), firstTwelveWarmImagesMs=statistics.mean(image_values[:12]),
                         lastTwelveWarmImagesMs=statistics.mean(image_values[-12:]))
        summary['additionalRuns'].append(extra)
        for item in r.get('rankingScale', []):
            summary['scale'].append(dict(item, run=r['runId'], backend=r['backend'], rankDistributionMs=distribution(item['rankMs'])))

args.root.mkdir(parents=True, exist_ok=True)
(args.root / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
fields = ['configuration', 'runId', 'round', 'id', 'query', 'inferenceMs', 'totalMs', 'saveMs', 'rankMs', 'correct']
with (args.root / 'measurements.csv').open('w', newline='', encoding='utf-8-sig') as stream:
    writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore'); writer.writeheader()
    for r in reports:
        for measure in r['images'] + r['queries']:
            writer.writerow(dict(measure, configuration=r['configuration'], runId=r['runId']))

def table_row(row):
    return (f"| {row['label']} | {row['coldInitMs']['median']:.0f} | {row['firstImageMs']['median']:.0f} | "
            f"{row['warmImageMs']['median']:.0f} / {row['warmImageMs']['p95']:.0f} | {row['warmImagesPerSecond']:.2f} | "
            f"{row['warmQueryMs']['median']:.0f} / {row['warmQueryMs']['p95']:.0f} | {row['observedMaxPssMiB']:.0f} |")

lines = ['# Galaxy S25: EmbeddingGemma on-device benchmark', '',
         'Device measured: Samsung SM-S931U, Qualcomm SM8750, Android 16 / API 36. Native Windows ADB port 5038.', '',
         f"Measured {summary['counts']['runs']} process-cold runs: {summary['counts']['imageInferences']} real image inferences and {summary['counts']['textInferences']} real text inferences. Thermal guards shortened later rounds. The image comparison uses photos 2-6 of the initial pass in each run, 10 timings per configuration. Every image was embedded again; cached vectors were not used for inference timing.", '',
         '| Backend / main threads | Cold engine init ms | First image ms | Warm image p50 / p95 ms | Photos / sec | Warm search p50 / p95 ms | Max observed PSS MiB |',
         '|---|---:|---:|---:|---:|---:|---:|']
lines += [table_row(row) for row in rows]
lines += ['', 'Thread counts configure the main encoder. LiteRT-LM 0.18.0 does not forward visionBackend.threadCount, so CPU 1 is not an entirely single-threaded image pipeline. Image jobs are serialized. PSS includes Android-accounted GPU memory; RSS alone can understate the GPU footprint. PSS was sampled at phase boundaries; RSS was sampled every 100 ms.', '',
          'Cold init is engine construction + initialize, with a fresh runtime cache directory. Full-model SHA verification is separate. The model was provisioned over USB, so no first-download speed is claimed. Cold means fresh app process, not flushed operating-system page cache. The first query follows image indexing and is not a text-only engine-cold test.', '',
          'The JPEG samples are 800 pixels wide; timing includes file read, native image preprocessing and inference, validation, and atomic production JSON index update. It excludes importing/resizing full-resolution camera photos, photo-library enumeration, EXIF extraction, application UI rendering, and checkpoint/report instrumentation. Search includes query embedding, vector validation, and ranking six photos.', '',
          f"APK: {summary['apkBytes'] / 1e6:.2f} MB ({summary['apkBytes'] / 1024**2:.2f} MiB). Separate downloaded model: {summary['modelBytes'] / 1e6:.2f} MB ({summary['modelBytes'] / 1024**2:.2f} MiB).", '',
          'Each 768-dimensional Float32 vector is exactly 3,072 bytes. The current six-photo JSON index also stores decimal floats, titles and paths:', '']
for row in rows:
    lines.append(f"- {row['label']}: {row['sixImageIndexBytes']['median']:,.0f} bytes total, about {row['sixImageIndexBytes']['median'] / 6:,.0f} bytes per photo including metadata and shared document overhead.")
lines += ['', 'The 1,000-photo duration below is a linear throughput estimate, not a scan of 1,000 distinct photos. It excludes large-index growth, import/thumbnail processing, and long-duration thermal effects:', '']
for row in rows:
    lines.append(f"- {row['label']}: approximately {row['estimated1000ImagesSeconds'] / 60:.1f} minutes / 1,000 photos from warm six-photo throughput.")

if summary['scale']:
    lines += ['', '## Ranking scale (synthetic cardinality)', '',
              'Each entry owns a separate Float32 array copied from one of the six real embeddings. This measures the current shared cosine + full-sort implementation, not retrieval accuracy on a large photo library. Native inference is excluded.', '',
              '| Backend | Entries | Ranking p50 / p95 ms | Raw vectors MiB |', '|---|---:|---:|---:|']
    for item in summary['scale']:
        lines.append(f"| {item['backend']} | {item['entries']:,} | {item['rankDistributionMs']['median']:.1f} / {item['rankDistributionMs']['p95']:.1f} | {item['rawVectorBytes'] / 1024**2:.1f} |")
        if 'jsonBytes' in item:
            lines += ['', f"For {item['entries']:,} entries, JSON encoding took {item['jsonEncodeMs']:.0f} ms and decoding {item['jsonDecodeMs']:.0f} ms; encoded file size {item['jsonBytes'] / 1e6:.2f} MB.", '']
    lines += ['', 'The demo rewrites the complete JSON index after every indexed photo. This scales poorly; use incremental database writes and binary vectors before a full-library product. At 10,000 photos, raw vectors alone are 30.72 MB; at 50,000, 153.60 MB. These exclude database, string, object, thumbnail, model and driver overhead.', '']
if summary['additionalRuns']:
    lines += ['', '## Additional process/cache and sustained checks', '',
              '| Run | Backend | Images | Init ms | Start / end battery C | First / last 12 warm image mean ms |', '|---|---|---:|---:|---:|---:|']
    for extra in summary['additionalRuns']:
        a, b = extra['startEnvironment'], extra['endEnvironment']
        tail = f"{extra['firstTwelveWarmImagesMs']:.0f} / {extra['lastTwelveWarmImagesMs']:.0f}" if 'warmImageMs' in extra else 'n/a'
        lines.append(f"| {extra['run']} | {extra['backend']} | {extra['imageCount']} | {extra['initMs']:.0f} | {a['batteryTemperatureC']:.1f} / {b['batteryTemperatureC']:.1f} | {tail} |")
    lines += ['', 'Repeated input photos keep workload comparable. These short charging runs do not establish power consumption, long-session thermals, or background execution behavior. For cache runs, the first run populates a shared cache and the second reuses it.', '']

lines += ['', '## Validation and reproducibility', '',
          'Two runs per configuration in forward and reverse order. Each run has 6 first-pass image embeddings, optional additional rounds until the thermal guard, and 6 first-pass + 24 warm short English queries. Correct top-1 retrieval: ' + ', '.join(f"{r['label']} {r['correctQueries']}/{r['totalQueries']}" for r in rows) + '. These six categories are a smoke test, not a quality benchmark.', '',
          'Charging, screen on, no thermal override or CPU affinity changes. Temperature range per configuration and raw timings are in summary.json and matrix/*.json. Thermal status is Android PowerManager status, not a direct measurement of CPU clock throttling.', '',
          f"Source content SHA256: `{receipt['source']['content_sha256']}`. APK build run: `{receipt['run_id']}`. Android assembleDebug + lintDebug and the same-source shared JVM/downloader checks passed.", '',
          'Official SDK sources: [thread setter](https://github.com/google-ai-edge/LiteRT-LM/blob/b2f686e2ed4718fb84ec398a61dd59ca0f0aff27/c/embedding_engine.cc#L154-L160), [Kotlin embedding engine](https://github.com/google-ai-edge/LiteRT-LM/blob/b2f686e2ed4718fb84ec398a61dd59ca0f0aff27/kotlin/java/com/google/ai/edge/litertlm/EmbeddingEngine.kt#L60-L82).', '',
          'Files: summary.json, measurements.csv, matrix/*.json. Pilot/preflight files are excluded from the balanced table. No personal gallery photos were accessed.', '']
markdown = '\n'.join(lines)
(args.root / 'REPORT.md').write_text(markdown, encoding='utf-8')

# Standalone HTML with a compact measured table and simple CSV links. No external assets or scripts.
columns = ['Backend / main threads', 'Cold init ms', 'First image ms', 'Image p50 / p95 ms', 'Photos / sec', 'Search p50 / p95 ms', 'Observed PSS MiB']
body_rows = []
for row in rows:
    values = [row['label'], f"{row['coldInitMs']['median']:.0f}", f"{row['firstImageMs']['median']:.0f}",
              f"{row['warmImageMs']['median']:.0f} / {row['warmImageMs']['p95']:.0f}", f"{row['warmImagesPerSecond']:.2f}",
              f"{row['warmQueryMs']['median']:.0f} / {row['warmQueryMs']['p95']:.0f}", f"{row['observedMaxPssMiB']:.0f}"]
    body_rows.append('<tr>' + ''.join('<td>' + html.escape(v) + '</td>' for v in values) + '</tr>')
document = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Galaxy S25 · Gemma benchmark</title>
<style>body{font:16px/1.6 system-ui,sans-serif;color:#17212a;max-width:1200px;margin:40px auto;padding:0 24px;background:#fafbfc}h1{font-size:30px}table{border-collapse:collapse;width:100%;background:white;font-size:14px}td,th{padding:13px 10px;text-align:right;border-bottom:1px solid #dfe4e8}th:first-child,td:first-child{text-align:left}th{background:#e8eef3}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:14px/1.7 system-ui;background:white;padding:20px;border:1px solid #dfe4e8}a{color:#005bbb}.scroll{overflow-x:auto}</style>
<h1>Galaxy S25 · EmbeddingGemma</h1><p>Samsung SM-S931U · Qualcomm SM8750 · Android 16 · 10 process-cold runs</p>
<p>Each value comes from real device inference. Image comparison uses photos 2-6 of each initial pass; thermal guards shortened later rounds. CPU thread counts apply to the main encoder; vision uses SDK defaults.</p><div class="scroll"><table><thead><tr>'''
document += ''.join('<th>' + html.escape(c) + '</th>' for c in columns) + '</tr></thead><tbody>' + ''.join(body_rows) + '</tbody></table></div>'
document += '<p><a href="measurements.csv">Raw CSV</a> · <a href="summary.json">Summary JSON</a> · <a href="REPORT.md">Full Markdown report</a></p><pre>' + html.escape(markdown) + '</pre></html>'
(args.root / 'report.html').write_text(document, encoding='utf-8')
print(json.dumps(dict(configurations=rows, counts=summary['counts'], additionalRuns=summary['additionalRuns'], scale=summary['scale']), indent=2))
