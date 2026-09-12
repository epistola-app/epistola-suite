---
type: test
scopes: [db]
audience: dev
title: The migrated schema is now asserted, not just the data in it.
---

`SchemaHygieneAppTest` runs against `pg_catalog` at the app level, where every module's migrations are merged. Two kinds of check: hygiene invariants that should hold of any healthy schema — no two indexes over the same columns of a table, no constraint name sitting at PostgreSQL's 63-character truncation limit, no partitioned index missing a partition — and the identity model itself, so a migration that half-applies or a later one that quietly undoes it fails here rather than at runtime. It found three real problems on its first run, only one of which was new.
