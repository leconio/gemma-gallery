"""Provision a debug app's private model using run-as, with no shared-storage ownership assumptions."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument("--serial",required=True)
parser.add_argument("--adb-port", type=int, default=5037)
parser.add_argument("--model", type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
spec=json.loads((root/"model-manifest.json").read_text())
model=args.model or root/"models"/spec["filename"]
if model.stat().st_size!=spec["size"]:
    raise RuntimeError("Local model is incomplete")
hasher=hashlib.sha256()
with model.open("rb") as stream:
    for block in iter(lambda:stream.read(4*1024*1024),b""):hasher.update(block)
if hasher.hexdigest()!=spec["sha256"]:
    raise RuntimeError("Local model checksum mismatch")
adb=["adb","-P",str(args.adb_port),"-s",args.serial]
app="com.spawnlau.gemmapocket"
target="files/models/"+spec["filename"]
existing=subprocess.run(adb+["shell","run-as",app,"sha256sum",target],capture_output=True,text=True)
if existing.returncode==0 and existing.stdout.startswith(spec["sha256"]):
    print("DEVICE_MODEL_VERIFIED_ALREADY",flush=True)
else:
    subprocess.run(adb+["shell","run-as",app,"mkdir","-p","files/models"],check=True)
    with model.open("rb") as stream:
        subprocess.run(adb+["exec-in","run-as",app,"dd","of="+target+".partial","bs=1048576"],stdin=stream,stdout=subprocess.DEVNULL,check=True)
    result=subprocess.check_output(adb+["shell","run-as",app,"sha256sum",target+".partial"],text=True)
    if not result.startswith(spec["sha256"]):
        raise RuntimeError("Device model checksum mismatch; partial file preserved")
    subprocess.run(adb+["shell","run-as",app,"mv",target+".partial",target],check=True)
    print("DEVICE_MODEL_VERIFIED",spec["sha256"],flush=True)
