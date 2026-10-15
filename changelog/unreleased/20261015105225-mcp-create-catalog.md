---
type: feat
scopes: [mcp]
title: MCP gains a create_catalog tool.
---

An assistant working in a fresh tenant could create templates, themes and stencils, but not the
catalog to put them in, so a build stopped until someone created it in the UI. `create_catalog`
creates an empty AUTHORED catalog through the same command the UI uses, and needs the same
`CATALOG_MANAGE` permission.
