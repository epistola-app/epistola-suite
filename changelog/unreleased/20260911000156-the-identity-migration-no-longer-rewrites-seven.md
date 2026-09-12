---
type: fix
scopes: [db]
audience: dev
title: The identity migration no longer rewrites seven tables to add a column.
---

`resource_id` was declared with `DEFAULT gen_random_uuid()`, and a volatile default makes Postgres rewrite the whole table under `ACCESS EXCLUSIVE` — seven times, on the tables an upgrading installation can least afford locked. Added nullable and backfilled instead, and the column keeps no default: the sync trigger assigns it, which is what lets a caller that supplies its own identity (a restore carrying the identities its snapshot recorded) be told apart from one that did not. A collision between a supplied identity and the incumbent at that address is now raised rather than silently resolved in the incumbent's favour, which would have discarded exactly what the restore was carrying. The registry backfill also asserts one row per resource, and the two constraints whose generated names Postgres would have truncated at 63 characters are named.
