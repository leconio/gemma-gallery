# Security policy

Security fixes target the current main branch. Include the commit or app version, platform, OS version and a minimal reproduction when reporting an issue. Device serial numbers, private photos and full library exports are not needed.

## Sensitive reports

When **Security → Report a vulnerability** is available on this repository, use GitHub's private vulnerability reporting form. If it is unavailable, open an issue asking for a private reporting channel without including exploit details or sensitive material. Do not publish credentials, personal photos, embeddings, local databases or unredacted logs in an issue.

Useful reports include unauthorized photo access, bypasses of system consent, unintended sharing, model-download integrity failures and unsafe handling of imported or cached files. Use public fixtures or disposable photos in reproductions.

## Data boundaries

Model downloads are pinned and checksum-verified. Photo indexing and inference run on the device. A Lab export omits pixels and vectors but may contain filenames, photo IDs, tags and descriptions; review it before sharing. See [privacy details](docs/privacy.md).
