---
type: feat
scopes: [catalog, api, mcp]
audience: user
breaking: true
title: Using another catalog's resource needs that catalog declared first, on every surface.
---

A write that would make a catalog use a resource of a catalog it does not depend on is refused with
`CATALOG_NOT_DECLARED`, naming the catalog to declare. Before, the write declared that catalog by
itself. In the web UI the pickers ask to add the dependency when such a resource is chosen; REST and
MCP clients declare it first, with the catalog's dependencies endpoint or
`update_catalog_dependencies`. References that were already there stay editable.
