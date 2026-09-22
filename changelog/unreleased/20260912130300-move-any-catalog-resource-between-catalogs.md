---
type: feat
scopes: [catalogs]
audience: user
maturity: alpha
title: Move or rename any catalog resource — a crude move that leaves nothing behind.
---

All seven resource types — templates, stencils, themes, fonts, images, code lists and variant attributes — can be moved to another authored catalog, renamed, or both. A required preview shows what will be rewritten, how many published references will break, and what blocks the move. Drafts, variant attributes and theme styles follow the move; published versions do not. The old address stops working at once: bookmarks, REST and MCP callers and queued generation that name it fail, published versions that use a moved font fail to render and lose a moved image, and a catalog whose published templates name the old address cannot be exported, released or snapshotted until they are republished. Alpha, behind the `resource-relocation` toggle; see [Catalog resource relocation](docs/catalog-resource-relocation.md) and [ADR 0025](docs/adr/0025-relocation-without-aliases.md).
