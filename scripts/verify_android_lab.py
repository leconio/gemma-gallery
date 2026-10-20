"""Real-runtime UI acceptance on a dedicated emulator, never on personal hardware."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = "com.spawnlau.gemmapocket"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb-port", type=int, default=5037)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--model", type=pathlib.Path, required=True)
    parser.add_argument("--reset-lab", action="store_true")
    parser.add_argument("--mumu-index", type=int)
    parser.add_argument("--mumu-manager", type=pathlib.Path,
                        default=pathlib.Path("C:/Program Files/Netease/MuMu/nx_main/MuMuManager.exe"))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") and args.mumu_index is None:
        parser.error("Use a dedicated emulator; physical-device installation is not authorized by this test")
    adb = ["adb", "-P", str(args.adb_port), "-s", args.serial]
    def call(*argv, **kwargs):
        return subprocess.run(adb + list(argv), check=True, **kwargs)
    def output(*argv):
        return subprocess.check_output(adb + list(argv), text=True).strip()
    if args.mumu_index is None:
        assert output("shell", "getprop", "ro.kernel.qemu") == "1"
    else:
        manager = args.mumu_manager
        identity = subprocess.check_output([str(manager), "sh", "-v", str(args.mumu_index), "-c", "cat /proc/sys/kernel/random/boot_id"], text=True).strip()
        assert identity and identity == output("shell", "cat", "/proc/sys/kernel/random/boot_id"), "ADB target must match the selected MuMu instance"
        assert output("shell", "getprop", "ro.product.cpu.abi") == "x86_64"
    assert output("shell", "getprop", "sys.boot_completed") == "1"
    run = ROOT / ".devflow/runs" / args.run_id
    receipt = json.loads((run / "receipt.json").read_text())
    assert receipt["targets"]["android-emulator"]["exit_code"] == 0
    artifacts = run / "targets/android-emulator/artifacts/app/build/outputs/apk"
    apks = [artifacts / "debug/app-debug.apk", artifacts / "androidTest/debug/app-debug-androidTest.apk"]
    for apk in apks:
        call("install", "-r", str(apk))
    call("shell", "am", "force-stop", APP)
    if args.reset_lab:
        # Only this app's experiment files, on the explicitly selected emulator.
        call("shell", "run-as", APP, "rm", "-f", "files/lab-v1.json", "files/lab-last-report.json")
    call("shell", "pm", "grant", APP, "android.permission.READ_MEDIA_IMAGES")
    call("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
    for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        call("shell", "settings", "put", "global", name, "0")
    subprocess.run([sys.executable, str(ROOT / "scripts/prepare_lab_fixtures.py")], check=True)
    fixtures = ROOT / ".cache/lab-fixtures"
    manifest = json.loads((fixtures / "manifest.json").read_text())
    call("shell", "mkdir", "-p", "/sdcard/Pictures/GemmaLab")
    for photo in manifest["fixtures"]:
        assert hashlib.sha256((fixtures / photo["file"]).read_bytes()).hexdigest() == photo["sha256"]
        destination = "/sdcard/Pictures/GemmaLab/" + photo["file"]
        call("push", str(fixtures / photo["file"]), destination, stdout=subprocess.DEVNULL)
        call("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + destination, stdout=subprocess.DEVNULL)
    subprocess.run([sys.executable, str(ROOT / "scripts/provision_model.py"), "--serial", args.serial,
                    "--adb-port", str(args.adb_port), "--model", str(args.model)], check=True)
    out = ROOT / "evidence/lab-emulator" / args.run_id
    out.mkdir(parents=True, exist_ok=True)
    (out / "fixtures.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    start = time.monotonic()
    success = False
    try:
        for method in ("features", "persisted"):
            if method == "persisted":
                call("shell", "am", "force-stop", APP)
            text = output("shell", "am", "instrument", "-w", "-r", "-e", "class", f"{APP}.LabFlowTest#{method}",
                          f"{APP}.test/androidx.test.runner.AndroidJUnitRunner")
            (out / f"{method}.txt").write_text(text, encoding="utf-8")
            print(text, flush=True)
            if "OK (1 test)" not in text:
                raise RuntimeError(f"Emulator {method} failed; see {out}")
        success = True
    finally:
        archive = subprocess.check_output(adb + ["exec-out", "run-as", APP, "tar", "-cf", "-", "files/lab-evidence"])
        (out / "ui-evidence.tar").write_bytes(archive)
        import tarfile
        with tarfile.open(out / "ui-evidence.tar") as tar:
            tar.extractall(out, filter="data")
        feature_result = out / "files/lab-evidence/feature-result.json"
        unchanged = feature_result.is_file() and json.loads(feature_result.read_text()).get("photosUnchanged") is True
        result = dict(status="passed" if success and unchanged else "failed", source=receipt["source"],
                      runId=args.run_id, serial=args.serial, api=output("shell", "getprop", "ro.build.version.sdk"),
                      modelSha256=json.loads((ROOT / "model-manifest.json").read_text())["sha256"],
                      apkSha256=hashlib.sha256(apks[0].read_bytes()).hexdigest(),
                      photosUnchanged=unchanged, elapsedSeconds=round(time.monotonic() - start, 2))
        (out / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result, indent=2), flush=True)
    assert unchanged, "Lab must not change system photos"

if __name__ == "__main__":
    main()
