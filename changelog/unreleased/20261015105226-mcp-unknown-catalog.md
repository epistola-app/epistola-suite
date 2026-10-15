---
type: fix
scopes: [mcp]
title: MCP list tools report an unknown catalog instead of returning an empty list.
---

`list_templates`, `list_themes`, `list_stencils` and `list_images` returned `[]` for a `catalogId`
that does not exist, which reads as "this catalog is empty". They now fail with a not-found error
that points to `list_catalogs`. A blank `catalogId` now means "all catalogs", as an omitted one
does, instead of failing on key validation.
