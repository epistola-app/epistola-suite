---
type: perf
scopes: [catalogs]
audience: dev
title: Stencil version lookups keep their tenant predicate.
---

Restores `tenant_key` to the `stencil_versions` joins in the stencil list, catalog export, and export conflict queries so they use `idx_stencil_versions_stable_parent` instead of scanning the table.
