---
type: feat
scopes: [catalogs]
audience: user
maturity: alpha
title: Move any catalog resource between catalogs, and rename it.
---

All seven resource types — templates, stencils, themes, fonts, images, code lists and variant attributes — can be moved to another authored catalog, renamed, or both. Reorganising has its own page: browse across catalogs, pick one destination for a selection or give a row its own, or move a template from its settings. A preview is required and reports what will be rewritten, what keeps resolving through an alias, and what blocks the move, including a cycle between catalogs that would make snapshots unrestorable. Published documents keep rendering, and exports carry the new addresses. Old addresses keep working for template and stencil pages, and for templates, stencils and attributes over REST and MCP. An address left behind stays reserved. Applying a move needs catalog management. Alpha, behind the `resource-relocation` toggle.
