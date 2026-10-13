---
type: fix
scopes: [catalog, codelists]
title: Catalog and code-list sources are limited to what an operator allows.
---

They could read any local file or internal address. **Action on upgrade:** if catalogs are read from disk, set `epistola.catalog.file-roots`; if catalogs or code lists live on an intranet, set `epistola.catalog.outbound.allow-private-networks` or `allowed-hosts` (Helm `catalog.fileRoots`, `catalog.outbound.*`).
