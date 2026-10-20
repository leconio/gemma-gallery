# Release preparation

Use a reviewed commit whose checks pass. Do not publish model weights, local evidence directories, signing material, personal photo data or the optional downloaded benchmark dataset.

## Validate the source

```sh
./gradlew :shared:jvmTest :app:lintDebug :app:assembleDebugAndroidTest
python3 scripts/test_downloader.py
./gradlew :app:assembleRelease :app:lintRelease
python3 scripts/check_release_apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

Build iOS on a Mac with `python3 scripts/build_ios.py`. For an unsigned device build, add `--device`. A successful build verifies compilation and bundled notices; device behavior needs separate acceptance.

Keep the model revision and checksum, dependency versions and wrapper checksum pinned. Preserve LICENSE, NOTICE and THIRD_PARTY_NOTICES.md in source archives and app bundles. Check new media against its source manifest. Demo recordings use 2× playback and pre-indexed photos; they are not latency benchmarks.

## Prepare distribution

The release APK produced by Gradle is unsigned. Sign it with a private key stored outside the repository and verify the signed artifact before uploading. Physical iPhone distribution needs the maintainer's own Apple signing and distribution setup. This repository does not include signing credentials or an automated publishing job.

Create a source archive from the reviewed commit with `git archive`. Keep build receipts and hashes with the release evidence. Release notes should state the model download size, platform requirements, limitations and whether device flows were tested.

## Public repository cutover

Confirm the code license and review the Git history, branches, tags, issues and existing artifacts that a visibility change will expose. A clean source archive does not remove older history from the original repository. Use a fresh source-only repository if the historical material should remain private; do not rewrite shared history without a separate decision.

After changing visibility, enable GitHub private vulnerability reporting, verify the README/media from a signed-out session, and run the public CI workflow. Publishing a repository, tag, signed APK or announcement is a separate release action.
