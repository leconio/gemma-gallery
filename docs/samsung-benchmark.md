# Android real-device performance benchmark

The debug APK includes an isolated `BenchmarkActivity`. It uses six debug-only public JPEG photos and the same embedding configuration, vector validation and ranking as the photo app. Its JSON storage measurements describe the original 0.4 demo harness. Version 0.5 uses SQLite and system photo IDs in production; historical JSON timings are not measurements of that new library pipeline. The benchmark never opens the personal photo library or writes the production index. Release builds do not include this activity or the sample JPEGs.

Each configuration starts a fresh app process and verifies the provisioned model's SHA256. The default runs initialize a fresh native runtime cache directory. A first six-photo pass is followed by four additional uncached embedding passes, then 30 text queries. Every image inference is real; the repeated photo inputs are for latency measurements, not a representative image-quality evaluation. The UI is kept foreground and screen-on, including after automatic locking between runs, because Samsung can freeze background processes.

CPU thread counts select `Backend.CPU(1/2/4/8)` for the main encoder. LiteRT-LM Android 0.18.0 does not forward the vision backend's thread count to native settings. A CPU 1 result is therefore **not** an entirely single-threaded vision pipeline. The GPU backend may also execute some operators on the CPU. Each engine receives one job at a time, matching the current production app and the SDK's serialized calls.

Choose a device explicitly with `adb devices -l`. The examples use the standard ADB port 5037. If your environment uses another server port, pass it consistently to every command.

```powershell
adb devices -l
python scripts/provision_model.py --serial '<device-serial>' --model '<verified-local-model-path>'
python scripts/run_benchmark.py --serial '<device-serial>' --output evidence/benchmark/matrix
```

Install the debug APK on the explicitly selected device before provisioning. The native model remains separate from the APK. The `--model` option can reuse the previously downloaded, pinned model without copying it into a new worktree or downloading it again.

Additional measurements:

```powershell
python scripts/run_benchmark.py --serial '<device-serial>' --configs gpu --repetitions 1 --image-rounds 1 --query-rounds 1 --scale --output evidence/benchmark/scale --tag scale
python scripts/run_benchmark.py --serial '<device-serial>' --configs gpu --repetitions 1 --image-rounds 39 --query-rounds 1 --output evidence/benchmark/sustained --tag sustained
python scripts/run_benchmark.py --serial '<device-serial>' --configs gpu --repetitions 2 --image-rounds 0 --query-rounds 0 --reuse-runtime-cache --output evidence/benchmark/cache --tag cache
```

The scale option ranks 1,000 / 10,000 / 50,000 synthetic entries with separately owned arrays copied from six real embeddings. This measures ranking cost and vector residency, not quality at those library sizes. JSON encoding/decoding is measured at 1,000 entries to avoid turning the measurement into a Java heap stress test. The sustained pass records temperature/thermal state and stops image rounds at Android thermal status MODERATE. Short charging tests cannot establish energy use or full-library background-scan reliability.

Use `scripts/analyze_benchmark.py` with the installed APK's build receipt to create the standalone HTML/Markdown report, JSON summary and CSV measurements. Keep first use, warmed inference, query embedding, ranking, storage and network download timings separate. These JSON storage measurements belong to the historical benchmark harness. The current system-photo library uses incremental SQLite updates and binary vectors.

## Full-resolution phone-camera photos

The 100-photo test uses distinct curated HDR+ scenes, downloaded as the official full-resolution `final.jpg` files from Google's public bucket. The chosen files are 12.19-13.13 MP and total 415,036,969 bytes; no upscaling or host-side re-encoding is performed. They are JPEG quality-95 outputs from the official pipeline reprocessing phone-camera raw bursts. Source attribution and the CC BY-SA 4.0 license are preserved in the manifest.

```powershell
python scripts/fetch_camera_photos.py --output .cache/camera100
python scripts/provision_benchmark_photos.py --serial '<device-serial>' --directory .cache/camera100
python scripts/run_benchmark.py --serial '<device-serial>' --configs gpu,cpu1,cpu2,cpu4,cpu8 --repetitions 1 --image-rounds 0 --query-rounds 4 --cooldown 20 --max-start-temperature 38 --dataset camera100 --output evidence/benchmark/camera100 --tag camera100
python scripts/analyze_camera_benchmark.py --directory evidence/benchmark/camera100 --apk evidence/GemmaPocket-Samsung-Benchmark.apk --receipt <installed-apk-build-receipt.json>
```

Every run imports and embeds all 100 photos from scratch. The importer is shared with production, including sampled decode, EXIF orientation, maximum 1024px and JPEG quality 90. Each stage is timed separately. The device must cool to battery <=38C and Android thermal status <=LIGHT before the next run; the temperature is not held exactly constant. There is one process-cold pass per setting, not a repeated cold-start confidence interval. The 100-photo queries measure latency without semantic-relevance labels; keep the six-photo correctness smoke test separate.
