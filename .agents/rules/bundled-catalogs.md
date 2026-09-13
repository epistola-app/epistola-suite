---
paths:
  - "**/catalogs/system/**"
  - "**/catalogs/demo/**"
---

# Bundled catalogs

Two catalogs ship inside the product: `system` (in `epistola-core`) and `demo` (in
`apps/epistola-demo`). Touching either one's resources means cutting a release of it.

- **Bump `release.version`** in that catalog's `catalog.json` — SemVer, strictly increasing.
- **Regenerate `release.fingerprint`.** Run the fingerprint test for the catalog you touched, paste
  the "actual" value it reports, and re-run it green.

The loaders detect change by **fingerprint, not by the version string**, so a stale fingerprint
ships unchanged content silently while claiming a new version. This is the single most-forgotten
step in the repository.

The demo catalog is the kitchen sink: every user-facing capability must be exercised there — a PR
blocker — with realistic variants and edge cases. New capability means new demo usage; a changed
signature means updated usage; a removed component means removed usage. If something genuinely
cannot be demonstrated, the PR says why.

Background: [`docs/catalog-versioning.md`](../../docs/catalog-versioning.md).

## Verify

```bash
./gradlew :modules:epistola-core:unitTest --tests "*BundledCatalogFingerprintTest"   # system
./gradlew :apps:epistola-demo:unitTest --tests "*DemoCatalogFingerprintTest"         # demo
```
