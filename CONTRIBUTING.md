# Contributing

Start with the [build instructions](README.md). Keep changes small and focused, and explain the behavior they fix or add. Changes to shared logic should cover Android and iOS behavior, including limited or revoked photo access where relevant.

## Before opening a pull request

Run shared tests, downloader tests and Android lint:

```sh
./gradlew :shared:jvmTest :app:lintDebug :app:assembleDebugAndroidTest
python3 scripts/test_downloader.py
```

For native iOS or packaging changes, also build on a Mac:

```sh
python3 scripts/build_ios.py
```

State which checks ran and which did not. Compilation does not establish that a device flow works. Native photo-management changes should be checked with disposable photos on an explicitly selected test device or simulator. Keep personal libraries intact and preserve OS consent prompts.

Use the pinned model and fixture manifests for reproducible experiments. Similarity scores are not calibrated probabilities. Report photo preparation, model inference, ranking and total query time separately, and distinguish cached-vector operations from initial indexing.

Do not commit model weights, private photos, local databases, exported reports, credentials or signing files. Use `tests/fixtures/lab-photos.json` for the public Lab cohort. Do not send personal images or device identifiers in issue attachments or logs. See [SECURITY.md](SECURITY.md) for sensitive reports.

The optional `.devflow.json` configuration is maintainer tooling for isolated Windows/WSL/macOS builds. It requires local project-dev presets; contributors can use the standard commands above without it.

Contributions are accepted under the project's [Apache-2.0 license](LICENSE). Upstream assets retain their own licenses; include source and license information when adding third-party material.
