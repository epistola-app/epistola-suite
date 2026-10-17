---
type: feat
scopes: [catalog, ui, mcp]
audience: user
title: The web UI and MCP work in the draft you choose.
---

A catalog with several drafts now shows which one a page works in, with its base and the releases it is behind, and links to the others. The draft is part of the address (`letters@2.0.0`), so a link to it can be shared, and every page that edits the catalog's content — templates and their editor, stencils, themes, attributes, code lists, fonts, images — reads and saves that draft. The catalog page opens, discards, renumbers and releases the chosen draft, and its release asks you to confirm each release the draft is behind. A catalog without a draft offers to open one from its latest release. Over MCP, the content tools take a `catalogVersion`, and new tools list, open, renumber, release and discard drafts. Discarding a draft that alone holds something still in use, such as the tenant's default theme, is now refused with what holds it instead of failing.
