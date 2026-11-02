---
type: fix
scopes: [mcp, security]
title: An MCP tool needs only the permission it lists, and the documentation lists every tool's.
---

Several MCP write tools asked for view permissions they do not list, because they read to do their
work and answer. A key scoped to Publisher could not release or deploy, and an Author-only key could
not update a template or mark one ready. Every tool now declares its permission in code and enforces
exactly that. The tool tables in the MCP documentation list each tool's permission, and
`upload_font` needs only `REFERENCE_EDIT`.
