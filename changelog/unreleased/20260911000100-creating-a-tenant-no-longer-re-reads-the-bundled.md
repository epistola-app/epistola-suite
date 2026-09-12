---
type: perf
scopes: [catalog, fonts]
audience: dev
title: Creating a tenant no longer re-reads the bundled catalog or seeds fonts one statement at a time.
---

Every `CreateTenant` installs the system catalog (in demo mode, once per signed-in user). The classpath catalog was read, schema-migrated and hashed again for every tenant — three manifest reads and a full pass over every resource detail and binary — and the eight bundled font families were written with twenty-four statements. `CatalogClient` now caches manifests, resource details and binaries for `classpath:` sources in a bounded Caffeine cache (32 MiB by source bytes, no TTL, the same shape as `FontByteCache`), and `CatalogFingerprintService` caches the per-resource fingerprints of such a source (the canonicalise-and-hash pass was most of what `RegisterCatalog` cost per tenant). That content cannot change while the process runs; `file:` and HTTP sources are never cached, and binaries are copied out on read. `FontCatalogWriter` writes a set of families in three statements: one multi-row upsert, one delete of the previous faces, one batch insert of the new ones. The canonicaliser also stops re-serialising every resource to a JSON tree just to look for an asset content URL; it reads it from the bound asset resource. Measured locally, full core suite: a tenant 75.9 → 56.4 ms, the font seed 15.6 → 4.4 ms, `RegisterCatalog` 5.8 → 2.6 ms. This is a tenant-creation latency change; on CI it is inside run-to-run noise.
