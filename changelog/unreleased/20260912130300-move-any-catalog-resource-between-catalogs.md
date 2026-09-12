---
type: feat
scopes: [catalogs]
audience: user
maturity: alpha
title: Move any catalog resource between catalogs, and rename it.
---

All seven resource types — templates, stencils, themes, fonts, assets, code lists and variant attributes — can be moved to another authored catalog, renamed, or both. Reorganising has its own page: browse across catalogs and pick one destination for a whole selection, let a row take its own, or move a single resource from its own page. A preview is required before execution and reports what will be rewritten, what will keep resolving through an alias, and what blocks the move — including one that would leave two catalogs depending on each other, which would otherwise surface much later as a tenant whose snapshots cannot be restored. An address a moved resource left behind is reserved until it is explicitly released. Alpha, behind the `resource-relocation` toggle.
