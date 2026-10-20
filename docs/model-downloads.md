# Model downloads

[Gemma Gallery](../README.md) contains the Android/iOS photo-search app. The [compact model repository on Hugging Face](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact) contains the optimized LiteRT-LM bundle, model card, build scripts and validation reports. The app and model are documented in separate repositories.

## Download files

| Model | File size | Download | Model card |
|---|---:|---|---|
| Compact text-vision bundle | **309.56 MB** (309,559,296 bytes) | [eg2-gallery-compact.litertlm](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact/resolve/ebec702ff792b837cbc5fd2910a8d9ac0981ae9c/eg2-gallery-compact.litertlm?download=true) | [Compact model card](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact) |
| Original text-vision bundle, current app default | 387.71 MB (387,710,976 bytes) | [embeddinggemma-2-text-vision-440m.litertlm](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/e301f74d5551b0c2641bd5cb4652a76239d5c5f8/embeddinggemma-2-text-vision-440m.litertlm?download=true) | [Upstream conversion](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm) |

Both downloads are public and licensed under Apache-2.0. Links above pin exact revisions. MB means 1,000,000 bytes.

Download the compact bundle with the Hugging Face CLI:

```sh
hf download spawnlau/embeddinggemma-2-text-vision-440m-compact eg2-gallery-compact.litertlm --revision ebec702ff792b837cbc5fd2910a8d9ac0981ae9c --local-dir models
```

The app's existing download flow and `python3 scripts/download_model.py` use [model-manifest.json](../model-manifest.json), which currently pins the original bundle. The compact download above is for integration and native inference with the configuration below.

## Compact model configuration

Use LiteRT-LM **0.18.0** with **256 maximum input tokens, 70 vision tokens per image, and 768 output dimensions**. Set the vision budget in both the engine configuration and embedding options, and enable embedding normalization. The [HF model card](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact#download-and-run) provides a runnable Python example and explains the text-query prefix.

The compact bundle is **20.2% smaller**. It removes unused input-size graphs, unreachable vision position entries and redundant serialized data, while retaining the tokenizer, transformer layers and existing weight precision. It was not retrained or further quantized. The [rebuild scripts](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact/tree/main/scripts) reproduce the published file from the pinned original bundle.

Native model loading and text/image embedding passed on Samsung Galaxy S25 with CPU and GPU. Full app indexing, Search, Tags, Similar and Groups flows with the compact bundle, plus iOS runtime validation, remain pending. The original bundle remains the current app default while that integration is validated. Longer text contexts, 140-token vision paths and custom image preprocessors require the upstream model or separate validation.

## Samsung Galaxy S25 GPU measurements

Android 16, SM-S931U / Qualcomm SM8750, LiteRT-LM 0.18.0. Both text and vision used GPU.

| Metric | Original | Compact | Reduction |
|---|---:|---:|---:|
| Model file size | 387.71 MB | 309.56 MB | 20.2% |
| Observed peak process memory (PSS) | 903.8 MiB | 768.8 MiB | 14.9% |
| Model initialization time | 2.87 s | 2.41 s | 16.1% |
| Text encoding latency (P50) | 29.25 ms | 28.58 ms | 2.3% |
| Image encoding latency (per image, P50) | 153.07 ms | 147.75 ms | 3.5% |

Each version had one GPU run with **73 English/Chinese queries and 20 images**. The original and compact outputs were bitwise identical on that cohort when compared on the same backend. This regression check is not a broad independent accuracy benchmark.

P50 is the median. Timings cover model calls, excluding file reads, additional app preprocessing, database search and UI work; initialization excludes download and checksum verification. PSS is sampled whole-process memory, not dedicated GPU memory. One run per version does not establish a stable speedup. Exact timings, memory samples and test conditions are in the [GPU report](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact/blob/main/reports/samsung-gpu.json).

## SHA-256 checksums

Compact `eg2-gallery-compact.litertlm`:

```text
a08a6bcbcba893d8e52dcf2ae05ce06847f942e3b0e5ea8b8df93f32aa23b027
```

Original `embeddinggemma-2-text-vision-440m.litertlm`:

```text
92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0
```

The compact model's [manifest](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact/blob/main/model-manifest.json) records its runtime contract and source. The [model card](https://huggingface.co/spawnlau/embeddinggemma-2-text-vision-440m-compact) links back to this page and the app source. See [third-party notices](../THIRD_PARTY_NOTICES.md) for attribution and licensing.
