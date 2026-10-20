# Search relevance in 0.5.1

Search hides photos whose cosine similarity is below **0.68**. If none pass, the app shows **No matching photos**. An empty query browses the accessible library. Android and iOS use the same shared filter on existing vectors; no rescan or extra model is needed. The cutoff applies equally to the whole library and individual albums. Raw ranking remains available to the isolated latency benchmark.

Filtering also removes hidden photos from the selection, so a later delete or organize action cannot operate on a photo hidden by the search. Editing an unsubmitted query keeps the previous filter active during library refreshes.

## Measurements

The cutoff was tuned with **73 English and Chinese queries against 12 real PhotoKit images** on the dedicated iPhone simulator: six public fixtures and six simulator stock photos. Both query cohorts contributed to tuning. They are exploratory samples, not an independent accuracy benchmark or a guarantee for personal libraries. See the [raw score snapshot](search-relevance-scores.json). No filenames or album metadata were given to the model.

The pinned 388 MB model used CPU inference with four threads, normalized 768-dimensional output, the retrieval query prefix and a 70-token image budget. Images used the system-library preparation path, at most 1024 pixels on the long side. Scores are model similarities, not calibrated probabilities or percentage confidence.

| Query | Relevant score(s) | Excluded example | Result at 0.68 |
| --- | --- | --- | --- |
| `a dog` | Dog 0.7307 | Cat 0.6464 | Dog only |
| `a cat` | Cat 0.7409 | Dog 0.6504 | Cat only |
| `pets` | Dog 0.7135, cat 0.6849 | Flowers 0.6435 | Both pets |
| `animals` | Cat 0.6913, dog 0.6857 | Flowers 0.6414 | Both animals |
| `a computer` | None | Bicycle 0.6073 | Empty |
| `a car` | None | Bicycle 0.6385 | Empty |
| `a motorcycle` | None | Bicycle 0.6765 | Empty |

All **28 queries for absent object categories** in these samples return no photos at 0.68. These include cars, computers, phones, aircraft, trains, food and several animals absent from this small library. This count excludes attribute and action challenges. The initial 0.67 candidate admitted the motorcycle/bicycle confusion; 0.68 removes it.

## Known errors and tradeoff

The model still makes errors. `a black and white dog` incorrectly retrieves the black and white cat at **0.6867**. It also returns the silver bicycle for `a red bicycle` at **0.6810**. The Chinese query `雪山` misses the mountain at **0.6799**, and `宠物` drops the cat at **0.6759**. Raising the cutoff removes false matches and valid results together. Fine-grained attribute verification needs more than a single cosine cutoff.

The default prioritizes removing weak results. Changing the pinned model, vision budget or embedding dimensions requires measuring the cutoff again. The current score snapshot does not measure retrieval quality on the separate 100-camera-photo latency dataset.

## Reproduce on the dedicated simulator

Build the app, then run these commands on macOS through project-dev with an explicit `GemmaPocket-` simulator UDID:

```text
python3 scripts/audit_ios_search.py --udid <UDID>
python3 scripts/audit_ios_search.py --udid <UDID> --validation
```

The opt-in simulator audit calls the same native embedding engine and query prefix as the app, then compares real query vectors with the real SQLite photo index. It restores only missing public fixtures. Output is `iosApp/build/search-score-evaluation.json`; copy it before running the next cohort. Query vectors are recorded only when the simulator is explicitly launched with `--search-score-audit`. Normal launches do not record them, and this hook is excluded from physical iOS builds.

The system-library UI acceptance checks that dog and cat queries exclude unrelated fixtures, `pets` keeps both animals, and computer/car/motorcycle queries show the empty state. Existing album, deletion, permission and restart checks still run. Shared tests cover cutoff boundaries, zero matches, multiple matches, hidden selections and refreshes during draft edits.
