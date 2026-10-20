# Lab experiments

Lab shares the KMP interface and domain logic on Android and iOS. Each experiment uses up to 200 selected, indexed system photos. Choose photos can filter filenames to make selection easier; filenames are never embedding inputs. The main photo library has no 200-photo limit.

## Tags

Create up to 20 tags. Each has a name, a description, independent text/example cutoffs, and optional positive/negative reference lists of up to 20 photos each. A photo cannot be both a positive and a negative example for the same tag.

Text mode embeds the description with the same prompt as photo search. The description vector is cached by description and model version. Example mode uses the highest positive cosine similarity; it also requires a margin of at least 0.02 over the highest negative score, when negatives exist. A tag without an indexed positive example is skipped in example mode. It does not silently use text instead.

Reference photos for all defined tags are excluded from candidates in both modes. References may be outside the selected candidate scope. Several tags may match the same candidate. Scores are similarities, not probabilities. Starting cutoffs are adjustable experiment settings and need validation against the intended collection.

Confirm, reject or clear a review. Confirmed labels remain available after restarting, even before another prediction run. Reviews save metadata and do not change examples or train model weights. Edit the tag to change its examples. Deleting a tag removes its reviews.

## Similar

Choose an indexed reference from the selected scope. Results contain up to ten other photos above the cutoff, ordered by cosine similarity with a stable tie-breaker. The query photo is excluded. No fallback fills an empty result with unrelated photos.

## Groups

Complete-link agglomeration requires every pair in an automatic group to meet the cutoff. It avoids linking unrelated endpoints through a chain of adjacent matches. Singletons remain unassigned. Each group shows a representative with the highest average similarity to its members.

Edit a group to rename it, select photos to move to another group, or create a new group from the selection. Selecting a new destination splits those photos out. Merge whole group moves all its members into an existing destination. Unassigned photos can also be selected and moved. Every photo keeps exactly one membership. Manual edits are marked and may no longer satisfy the original automatic cutoff. These operations change only Lab metadata; they do not create system albums, move files or delete originals.

## Storage and runtime

Lab uses the existing normalized image vectors. New descriptions share the native engine and inference mutex with scanning/search. Pause scanning before running an experiment. Text embedding time includes inference; example matching, Similar and Groups reuse cached image vectors. Displayed durations cover the run, not initial photo indexing.

Definitions, cached description vectors, reviews and groups are saved atomically in `lab-v1.json`. Unavailable photo IDs are pruned after a real provider snapshot, including when access is revoked. Pending index loading does not remove accessible references. Changed image revisions invalidate saved grouping runs. An unreadable Lab file is retained and writes are disabled instead of overwriting it.

Export report opens the native share sheet with JSON metadata, raw scores, cutoffs and timings. It omits pixels and vectors. The app also maintains `lab-last-report.json` locally. Input changes clear stale transient results/reports. Uninstalling or clearing app data removes Lab metadata.

## MuMu acceptance

Use an explicitly selected MuMu instance. The host script verifies that the ADB connection and MuMu shell have the same kernel boot ID before installing anything. It adds 14 uniquely named test photos to `Pictures/GemmaLab`, leaves existing photos intact, provisions a checksum-verified model, and drives the normal production interface. Only the 14 fixture photos are selected for Lab experiments. The library itself may contain other photos.

```powershell
& "$env:USERPROFILE\Tools\dev.ps1" build -Project . -Target android-emulator
python scripts/verify_android_lab.py --serial '<MuMu ADB address>' --mumu-index 2 --run-id '<build run_id>' --model '<verified .litertlm file>'
```

Pass `--adb-port` if your ADB server uses a non-default port. A custom MuMu installation can be selected with `--mumu-manager`.

The x86_64 APK uses the real LiteRT-LM native library and model, without ARM translation or mock vectors. Tests exercise text/example labels, confirmation, Similar and its empty state, automatic groups, rename/split/merge, the export sheet, and a second invocation after force-stopping the app. A provider inventory checks that system photo IDs and byte sizes remain unchanged during the feature flow. Screenshots, raw reports, APK/source hashes and results are saved under `evidence/lab-emulator/<run_id>`.

The fixture set contains six reference photos, six independent photos of the same categories, and two other categories. URLs, roles and SHA-256 values are pinned in `tests/fixtures/lab-photos.json`. Categories are test metadata and are not passed to the model. This small run verifies functionality; it is not a general accuracy benchmark. MuMu timings are not measurements of physical-phone performance. The default Android build remains arm64-v8a for phones. iOS build checks are separate from MuMu UI acceptance.
