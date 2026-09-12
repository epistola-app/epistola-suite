---
type: docs
scopes: [catalog]
audience: dev
title: ADR 0020 (accepted) records where a catalog resource's address lives.
---

Review of #869 spotted that a resource's current address is stored twice — on its own row and on its `catalog_resources` registry row, kept in step by a trigger. The ADR re-examines that before the branch merges and keeps it: making the registry the sole holder would trade seven domain-checked key columns (`TEMPLATE_KEY`, `ASSET_KEY`, …) for one untyped `TEXT`, and cost the import path its `ON CONFLICT` targets. A composite foreign key that would have made the copy structurally impossible to diverge was checked against PostgreSQL 18 and rejected: `uuid` and `text` key columns have no equality operator, so it works for six types and fails for `assets`.
