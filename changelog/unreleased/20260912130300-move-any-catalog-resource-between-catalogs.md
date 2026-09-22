---
type: feat
scopes: [catalogs]
audience: user
maturity: alpha
title: Move any catalog resource between catalogs, and rename it — a crude move that leaves nothing behind.
---

All seven resource types — templates, stencils, themes, fonts, images, code lists and variant attributes — can be moved to another authored catalog, renamed, or both. Reorganising has its own page: browse across catalogs, pick one destination for a selection or give a row its own, or move a template from its settings. A preview is required and reports what will be rewritten, how many published references will break, and what blocks the move, including a cycle between catalogs that would make snapshots unrestorable. Drafts, variant attributes and theme styles follow the move; published versions do not. The old address stops working at once and can be reused: bookmarks, REST and MCP callers, queued generation requests and published versions that still name it fail, and a catalog whose latest published versions name a moved resource cannot be exported, released or snapshotted until they are republished. Applying a move needs catalog management. Alpha, behind the `resource-relocation` toggle.
