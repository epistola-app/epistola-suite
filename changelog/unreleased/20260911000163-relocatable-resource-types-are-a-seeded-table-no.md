---
type: refactor
scopes: [db]
audience: dev
title: Relocatable resource types are a seeded table, not a `CHECK`.
---

Adding an eighth type is now an `INSERT` rather than dropping and recreating a constraint, matching how `asset_types` already works. Two constraints on `catalog_release_publications` whose generated names PostgreSQL had truncated are renamed, so a later migration can reference them safely.
