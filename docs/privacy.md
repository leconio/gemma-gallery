# Privacy and local storage

The app processes accessible system photos on the device. Its inference inputs are prepared image pixels and the text you enter. Filenames and album names are display and organization metadata; they are not image embedding inputs.

## Stored by the app

- A checksum-verified model file and any partial download.
- SQLite records for accessible photo IDs, revisions and normalized vectors.
- Thumbnail and runtime caches.
- Lab definitions, cached description vectors, reviews and local groups.
- The latest Lab report. Legacy versions may also leave a backup index and old private image copies for recovery.

Embeddings, descriptions, filenames and labels can reveal information about a collection even when the original pixels are absent. Treat copied app data and reports as sensitive.

Revoking photo access hides unavailable results and prunes inaccessible index records after a provider snapshot. This is not a secure-erasure guarantee for prior backups or OS-managed storage. Clearing app data or uninstalling removes this installation's local data; OS photo-library deletion and recovery are managed separately by the system.

## Network and sharing

The app's download code contacts Hugging Face and may use the configured checksum-matched mirror. Those services receive normal download requests. The app does not send photo pixels, vectors or search text for remote inference. No account, analytics or advertising integration is included in the project code.

Sharing selected photos opens the system share sheet. The destination you select receives the shared files. A Lab report omits pixels and vectors but includes experiment metadata, which can contain photo IDs, filenames, tag names, descriptions, scores and settings. Review it before sending it elsewhere.

System Photos/gallery synchronization, OS backups and third-party share destinations have their own behavior. Cloud-only photos must be available locally before this version can index them; the app does not automatically download the originals from the photo cloud.

## Native photo actions

Android 11+ trash and folder moves act on actual system photos with system consent. Moving a photo to another folder changes its file location. On iOS, adding an asset to an album changes membership without moving or copying the original. Deletion requires the OS confirmation flow. Lab tag and group edits only change app-local metadata.
