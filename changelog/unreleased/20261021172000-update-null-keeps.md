---
type: fix
scopes: [api]
audience: user
title: REST updates never read null as "remove"; an empty description or an explicit flag does.
---

As the contract now says, an omitted or null member of an update keeps its value. An empty (or
whitespace-only) `description` removes a stencil's or a theme's, as it already did a template's.
`updateCatalogRelease` takes `clearDefaultTheme: true` to remove a draft's default theme, and
`updateAttribute` takes `clearCodeListBinding: true` to move a bound attribute to inline
`allowedValues`; either flag beside the member it removes is refused with 400.
