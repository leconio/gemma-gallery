"""Run the debug-only public-photo benchmark on one explicitly selected Android device."""
import argparse
import datetime as dt
import json
import pathlib
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb-port', type=int, default=5037)
parser.add_argument('--configs', default='cpu1,cpu2,cpu4,cpu8,gpu')
parser.add_argument('--repetitions', type=int, default=2)
parser.add_argument('--image-rounds', type=int, default=4, help='Warm rounds after the first six images')
parser.add_argument('--query-rounds', type=int, default=4, help='Warm rounds after the first six queries')
parser.add_argument('--cooldown', type=float, default=12)
parser.add_argument('--scale', action='store_true')
parser.add_argument('--reuse-runtime-cache', action='store_true', help='Use the same runtime cache across launches; the first launch can still populate an empty cache')
parser.add_argument('--output', type=pathlib.Path, required=True)
parser.add_argument('--tag', default='matrix')
parser.add_argument('--dataset', default='builtin')
parser.add_argument('--max-start-temperature', type=float, default=0, help='If nonzero, wait until battery C <= this and thermal status <= LIGHT')
args = parser.parse_args()
adb = ['adb', '-P', str(args.adb_port), '-s', args.serial]
app = 'com.spawnlau.gemmapocket'
args.output.mkdir(parents=True, exist_ok=True)

def invoke(*commands, check=True):
    result = subprocess.run(adb + list(commands), capture_output=True, text=True, timeout=90)
    if check and result.returncode:
        raise RuntimeError(f'ADB {commands[:3]} failed: {result.stdout} {result.stderr}')
    return result

if invoke('get-state').stdout.strip() != 'device':
    raise RuntimeError('Explicitly selected device is not online')
identity = {prop: invoke('shell', 'getprop', prop).stdout.strip() for prop in [
    'ro.product.manufacturer', 'ro.product.model', 'ro.soc.model', 'ro.build.version.release', 'ro.build.version.sdk']}
(args.output / 'device.json').write_text(json.dumps(identity, indent=2), encoding='utf-8')
print(json.dumps({'device': identity, 'port': args.adb_port}), flush=True)
stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
configurations = args.configs.split(',')
for repetition in range(args.repetitions):
    # Counterbalance ordering across two repetitions; log device temperature in each app report.
    order = configurations if repetition % 2 == 0 else list(reversed(configurations))
    for config in order:
        backend = 'GPU' if config == 'gpu' else 'CPU'
        threads = int(config.removeprefix('cpu')) if backend == 'CPU' else 4
        run_id = f'{args.tag}-{stamp}-r{repetition + 1}-{config}'
        invoke('shell', 'am', 'force-stop', app)
        if args.max_start_temperature:
            cool_deadline = time.monotonic() + 900
            while True:
                temperature = float(invoke('shell', 'cmd', 'battery', 'get', 'temp').stdout.strip()) / 10
                thermal_text = invoke('shell', 'dumpsys', 'thermalservice').stdout
                thermal_status = int(re.search(r'Thermal Status:\s*(\d+)', thermal_text).group(1))
                if temperature <= args.max_start_temperature and thermal_status <= 1:
                    break
                if time.monotonic() > cool_deadline:
                    raise TimeoutError('Device has not returned to the requested starting temperature')
                print(json.dumps({'coolingBefore': run_id, 'batteryTemperatureC': temperature, 'thermalStatus': thermal_status}), flush=True)
                time.sleep(20)
        invoke('shell', 'input', 'keyevent', '224')
        launch_start = time.perf_counter()
        launch = invoke('shell', 'am', 'start', '-W', '-n', app + '/.BenchmarkActivity',
            '--es', 'runId', run_id, '--es', 'backend', backend, '--ei', 'threads', str(threads),
            '--es', 'dataset', args.dataset,
            '--ei', 'imageRounds', str(args.image_rounds), '--ei', 'queryRounds', str(args.query_rounds),
            '--ez', 'scale', str(args.scale).lower(), '--ez', 'coldCache', str(not args.reuse_runtime_cache).lower())
        launch_ms = (time.perf_counter() - launch_start) * 1000
        print(json.dumps({'started': run_id, 'hostAdbLaunchMs': launch_ms}), flush=True)
        destination = args.output / (run_id + '.json')
        last_phase = None
        last_progress = time.monotonic()
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline:
            time.sleep(3)
            result = invoke('exec-out', 'run-as', app, 'cat', f'files/benchmarks/{run_id}.json', check=False)
            if result.returncode:
                continue
            try:
                report = json.loads(result.stdout)
            except json.JSONDecodeError:
                continue
            report['hostAdbLaunchMs'] = launch_ms
            report['adbActivityLaunchOutput'] = launch.stdout
            destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
            if report.get('phase') != last_phase:
                print(json.dumps({'run': run_id, 'phase': report.get('phase'), 'images': len(report.get('images', [])),
                    'queries': len(report.get('queries', [])), 'status': report.get('status')}), flush=True)
                last_phase = report.get('phase')
                last_progress = time.monotonic()
            elif time.monotonic() - last_progress > 75:
                report['hostRejectedReason'] = 'No phase progress for 75 seconds; inspect process, screen state and native logs.'
                destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
                raise TimeoutError(f'No progress: {run_id}; partial report preserved')
            if report.get('status') in ('passed', 'failed'):
                if report['status'] != 'passed':
                    raise RuntimeError(f'{run_id}: {report.get("error")}')
                print(json.dumps({'completed': run_id, 'elapsedMs': report['totalElapsedMs'],
                    'correctQueries': report['correctQueries'], 'peakRssKb': report['peakSampledRssKb'],
                    'environment': report['endEnvironment']}), flush=True)
                break
        else:
            raise TimeoutError(f'Benchmark did not finish: {run_id}; partial reports preserved')
        invoke('shell', 'am', 'force-stop', app)
        if args.cooldown:
            time.sleep(args.cooldown)
print('ALL_REQUESTED_RUNS_COMPLETED', flush=True)
