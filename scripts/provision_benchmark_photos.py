"""Stream the public test originals into one debug app's private benchmark directory."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb-port', type=int, default=5037)
parser.add_argument('--directory', type=Path, required=True)
parser.add_argument('--dataset', default='camera100')
args = parser.parse_args()
assert re.fullmatch('[a-zA-Z0-9_-]{1,64}', args.dataset)
manifest = json.loads((args.directory / 'manifest.json').read_text())
assert len(manifest) == 100
for item in manifest:
    assert re.fullmatch(r'camera-\d{3}\.jpg', item['filename'])
    content = (args.directory / item['filename']).read_bytes()
    assert len(content) == item['bytes'] and hashlib.sha256(content).hexdigest() == item['sha256']
adb = ['adb', '-P', str(args.adb_port), '-s', args.serial]
app = 'com.spawnlau.gemmapocket'
target = f'files/benchmarks/datasets/{args.dataset}'
subprocess.run(adb + ['shell', 'run-as', app, 'mkdir', '-p', target], check=True)
names = [i['filename'] for i in manifest] + ['manifest.json', 'dataset-info.json']
# Use the adb sync protocol for large public datasets. Verify private copies before
# removing only these explicitly named staging files. No shell glob or recursive rm.
staging = '/data/local/tmp/gemma-pocket-public-' + args.dataset
subprocess.run(adb + ['shell', 'mkdir', '-p', staging], check=True)
def remote_hashes(folder, private=False):
    prefix = ['exec-out', 'run-as', app, 'sha256sum'] if private else ['exec-out', 'sha256sum']
    result = subprocess.run(adb + prefix + [folder + '/' + name for name in names], capture_output=True, text=True, timeout=90)
    return {line.split()[-1].split('/')[-1]: line.split()[0] for line in result.stdout.splitlines()
            if re.match(r'^[a-f0-9]{64}\s', line)}

private_hashes = remote_hashes(target, True)
staging_hashes = remote_hashes(staging)
for index, name in enumerate(names, 1):
    expected = hashlib.sha256((args.directory / name).read_bytes()).hexdigest()
    if private_hashes.get(name) == expected:
        continue
    for attempt in range(5):
        try:
            if staging_hashes.get(name) != expected:
                push = subprocess.run(adb + ['push', '-Z', str((args.directory / name).resolve()), staging + '/' + name],
                                      capture_output=True, text=True, timeout=90)
                if push.returncode: raise RuntimeError(push.stderr[-800:])
            copied = subprocess.run(adb + ['shell', 'run-as', app, 'cp', staging + '/' + name, target + '/' + name],
                                    capture_output=True, text=True, timeout=30)
            if copied.returncode: raise RuntimeError(copied.stderr[-800:])
            checked = subprocess.check_output(adb + ['exec-out', 'run-as', app, 'sha256sum', target + '/' + name], text=True)
            assert checked.split()[0] == expected
            break
        except Exception as error:
            if attempt == 4: raise
            print(json.dumps({'retryFile': name, 'attempt': attempt + 1, 'reason': str(error)[-300:]}), flush=True)
            staging_hashes.pop(name, None)
            time.sleep(2)
            subprocess.run(adb + ['wait-for-device'], check=True, timeout=60)
    time.sleep(.15)
    if index % 10 == 0: print(json.dumps({'verifiedThroughFile': index}), flush=True)
hashes = subprocess.check_output(adb + ['exec-out', 'run-as', app, 'sha256sum'] +
                                [target + '/' + i['filename'] for i in manifest], text=True)
actual = {line.split()[-1].split('/')[-1]: line.split()[0] for line in hashes.splitlines()
          if re.match(r'^[a-f0-9]{64}\s', line)}
assert all(actual.get(i['filename']) == i['sha256'] for i in manifest)
subprocess.run(adb + ['shell', 'rm', '-f'] + [staging + '/' + name for name in names], check=True)
subprocess.run(adb + ['shell', 'rmdir', staging], check=True)
print(json.dumps({'provisionedAndDeviceSha256Verified': len(manifest), 'originalBytes': sum(i['bytes'] for i in manifest),
                  'dataset': args.dataset, 'adbPort': args.adb_port}), flush=True)
