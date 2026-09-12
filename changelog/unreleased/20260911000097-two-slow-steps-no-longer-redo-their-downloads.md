---
type: ci
scopes: [build]
audience: dev
title: Two slow steps no longer redo their downloads.
---

The frontend SBOM ran `npx --yes @cyclonedx/cdxgen` unpinned, which fetched cdxgen from npm on every run and took over five minutes on two of the last six runs; the version is pinned and the npx cache is kept between runs. The Playwright browser cache was keyed on the hash of the whole version catalog, so every dependency bump re-downloaded Chromium and its apt packages (up to three minutes); it is keyed on the Playwright version alone.
