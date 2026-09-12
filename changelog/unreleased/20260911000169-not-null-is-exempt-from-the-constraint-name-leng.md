---
type: test
scopes: [db]
audience: dev
title: `NOT NULL` is exempt from the constraint-name-length check.
---

PostgreSQL 18 made `NOT NULL` a real catalog constraint where 17 kept it as `pg_attribute.attnotnull` alone, so two long-named columns appeared in `SchemaHygieneAppTest` overnight with no schema change behind them. They are exempt because the instability the check is about cannot reach them: the name comes from a single column, and every migration in the tree uses `SET NOT NULL` / `DROP NOT NULL` rather than naming one.
