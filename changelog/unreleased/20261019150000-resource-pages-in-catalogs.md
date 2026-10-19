---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A template, theme, stencil, image, font, code list or attribute is addressed within its catalog.
---

A resource's pages and actions moved under its catalog: a template is at `/tenants/{tenant}/catalogs/{catalog}/templates/{key}` (with its settings, data contract, variants and editor below it), and likewise `…/catalogs/{catalog}/themes/{key}`, `…/stencils/{key}`, `…/code-lists/{key}`, and the images' and fonts' content. Long-term work keeps its segment, as in `…/catalogs/letters@<draft>/templates/invoice`. The old addresses, `/tenants/{tenant}/templates/{catalog}/{key}` and the like, are no longer pages; update bookmarks. Embedding hosts that build these paths themselves need the new shape; the bridge's `navigate` message is unchanged. Audit links to an attribute or a font now open its catalog's list.
