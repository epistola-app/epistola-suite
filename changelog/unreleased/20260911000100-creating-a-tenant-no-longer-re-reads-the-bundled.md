---
type: perf
scopes: [catalog, fonts]
audience: dev
title: Creating a tenant no longer re-reads the bundled catalog or seeds fonts one statement at a time.
---

Every `CreateTenant` installs the system catalog — in demo mode, once per signed-in user. The classpath catalog was read, schema-migrated and hashed again for every tenant, and the eight bundled font families were written with twenty-four statements. `CatalogClient` now caches manifests, resource details and binaries for `classpath:` sources in a bounded Caffeine cache (32 MiB by source bytes, no TTL), and `CatalogFingerprintService` caches their per-resource fingerprints — that content cannot change while the process runs, and `file:` and HTTP sources are never cached. `FontCatalogWriter` writes a set of families in three statements rather than twenty-four. Measured locally: a tenant 75.9 to 56.4 ms, the font seed 15.6 to 4.4 ms, `RegisterCatalog` 5.8 to 2.6 ms. On CI it is inside run-to-run noise.
