# Third-party notices

The project [LICENSE](LICENSE) covers original project code. The components and media below retain their own licenses. Building the app downloads dependencies; it does not change their licenses.

| Component | Source / version | License | Included here |
| --- | --- | --- | --- |
| Gradle wrapper | [Gradle 9.3.1](https://github.com/gradle/gradle/tree/v9.3.1) | [Apache-2.0](https://github.com/gradle/gradle/blob/v9.3.1/LICENSE) | Wrapper scripts and JAR |
| EmbeddingGemma 2 Text-Vision 440M | [Pinned community conversion](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/tree/e301f74d5551b0c2641bd5cb4652a76239d5c5f8), based on [Google's model](https://huggingface.co/google/embeddinggemma-2) | Apache-2.0 | Manifest only; weights download separately |
| LiteRT-LM | [v0.18.0](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.18.0) | [Apache-2.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/LICENSE) | Build dependency; Swift source/binary download is pinned |
| Kotlin, coroutines and serialization | [JetBrains](https://github.com/JetBrains/kotlin) / [Kotlin libraries](https://github.com/Kotlin) | Apache-2.0 | Build dependencies |
| Compose Multiplatform | [JetBrains Compose](https://github.com/JetBrains/compose-multiplatform) | Apache-2.0 | Build dependency |
| AndroidX Activity, Compose and test libraries | [AndroidX](https://android.googlesource.com/platform/frameworks/support/) | Apache-2.0 | Build dependencies |
| Okio | [Square Okio](https://github.com/square/okio) | Apache-2.0 | Build dependency |
| Unsplash photos | [Unsplash License](https://unsplash.com/license) | Unsplash License | Six debug JPEGs; photos visible in demo media |
| HDR+ Burst Photography Dataset | [Dataset and paper](https://hdrplusdata.org/dataset.html) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Download script only; dataset is not included |

## Model and runtime

[model-manifest.json](model-manifest.json) records the model's exact revision, byte count and SHA-256. The pinned model repository declares Apache-2.0 and allows anonymous downloads. The app uses the text and vision variant; it does not implement audio or video embedding.

[iosApp/sdk-lock.json](iosApp/sdk-lock.json) pins the official LiteRT-LM release commit, Swift source blob IDs and native binary checksum. The preparation script preserves the downloaded upstream source files and LICENSE. It generates a local Package.swift adapter. Native SDK distributions can contain additional third-party components; retain the notices supplied with the upstream SDK when redistributing it.

The app bundles this file, LICENSE and NOTICE in Android assets and the iOS app bundle. These notices are an inventory of the components used by this demo, not a grant of additional rights in upstream code or media.

## Photos and demo recordings

The six JPEGs under `app/src/debug/assets/samples/` are used only by debug benchmarks and test fixtures. Their fixed source URLs, license link and SHA-256 values are in [samples.json](app/src/debug/assets/samples.json). Release APKs exclude the debug photos and benchmark activity.

[tests/fixtures/lab-photos.json](tests/fixtures/lab-photos.json) records all 14 Unsplash photos used in the Lab tests and recordings, including their URLs, roles and checksums. Additional fixtures are downloaded on demand. Photographs in the recordings remain under the Unsplash License; the app UI and authored captions are part of the project. See [media provenance](docs/media/README.md).

The optional 100-photo HDR+ benchmark downloads JPEGs into an ignored cache. The download script writes source URLs, attribution, checksums and the CC BY-SA 4.0 license into its manifests. Credit: Samuel W. Hasinoff, Dillon Sharlet, Ryan Geiss, Andrew Adams, Jonathan T. Barron, Florian Kainz, Jiawen Chen and Marc Levoy, *Burst photography for high dynamic range and low-light imaging on mobile cameras*, SIGGRAPH Asia 2016. Preserve the dataset's license and attribution when sharing downloaded or adapted dataset material.
