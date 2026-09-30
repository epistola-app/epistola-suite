---
type: fix
scopes: [templates, api, mcp]
audience: user
issues: [1028]
title: A template or stencil with two address blocks is refused on save, import and publish.
---

The address block allows one per document, but only the editor enforced it. A document written over REST, over MCP or by catalog import could hold two, and rendered with the second one silently dropped. The server now refuses it with `TEMPLATE_COMPONENT_TOO_MANY`, reading the limit from the component registry. Documents already stored with two keep rendering as before.
