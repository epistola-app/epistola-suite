---
type: perf
scopes: [db]
audience: dev
title: A duplicate index on `api_keys` is gone.
---

`key_hash` is declared `UNIQUE`, which already indexes it; the explicit `idx_api_keys_key_hash` alongside it has been maintained by every key write since the table was created, and could never be chosen over the unique index.
