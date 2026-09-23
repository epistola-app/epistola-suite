---
type: perf
scopes: [catalog]
audience: dev
title: The latest catalog release is found by the database, not by reading every release.
---

Asking for a catalog's latest release read every release row it had and picked the highest version in application code, because a version sorts by its components rather than as text — `1.10.0` is newer than `1.9.0` but sorts before it. `catalog_releases` now carries the version's parts as generated columns, so the answer is one indexed row and the cost stops growing with each release. The columns are generated from the version itself, so they cannot drift and are filled for rows that arrive by a restore. A label that is not `MAJOR.MINOR.PATCH`, which bundled and imported catalogs may carry, keeps empty parts, sorts last and falls back to when it was released. The unused release history the same query returned is gone; nothing rendered it.
