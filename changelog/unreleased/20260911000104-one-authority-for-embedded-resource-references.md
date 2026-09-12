---
type: refactor
scopes: [catalogs]
audience: dev
title: One authority for embedded resource references.
---

The resource graph, catalog relocation, and catalog export walked template and stencil JSON with four separate hand-written traversals that had already diverged on which shapes count as a reference. They now share `ResourceReferenceSites`, so a new reference shape is declared once.
