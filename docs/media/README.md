# Demo media

These three recordings show the pre-release Gemma Pocket 0.6.0 build on a MuMu Android 15 x86_64 instance, using the real LiteRT-LM runtime and pinned model. The project has since been renamed Gemma Gallery. Playback is **2× speed**. English subtitles were reviewed with the local Humanizer and checked against the implemented behavior. Click indicators follow recorded UI taps. There is no audio track.

| File | Demonstrates |
| --- | --- |
| [tags-2x.mp4](tags-2x.mp4) | Tag definition, text/example matching and confirmation |
| [similar-2x.mp4](similar-2x.mp4) | Reference-photo search and changing the cutoff |
| [groups-2x.mp4](groups-2x.mp4) | Automatic groups, rename, split and merge |

The 14 photos were indexed before recording. The clips do not show initial model download or first-scan latency, and emulator UI timings are not phone-performance measurements.

All pictured photos come from the public fixture cohort in [lab-photos.json](../../tests/fixtures/lab-photos.json), which records their URLs and SHA-256 values. The source photographs retain the [Unsplash License](https://unsplash.com/license). Project UI and authored captions are covered by the project code license. No personal photo library is shown.

The `sourceCommit` in the media manifest identifies that pre-release recording build. This public source-only repository starts with a new Git history.
