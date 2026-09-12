---
type: perf
scopes: [db]
audience: dev
title: Six duplicate identity indexes removed.
---

Each re-keyed table carried a unique constraint on `(tenant_key, resource_id)` that became an exact duplicate of its primary key once the key swapped, and every foreign key bound to the duplicate rather than the key. Dropped at the swap, before the dependants' foreign keys are added, so they bind to the primary key.
