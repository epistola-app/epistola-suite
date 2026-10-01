---
type: fix
scopes: [stencils, catalog, api]
audience: user
issues: [1024, 983]
title: Stencils with the same key in different catalogs are no longer mixed up.
---

Stencil keys are unique only within a catalog, but several places matched stencil instances by key alone. Upgrading `acme/letterhead` in a template also rewrote any `brand-b/letterhead` instance there with acme's content. The stencil usage lists, the version usage endpoint and the in-use check behind deleting a stencil counted the other catalog's instances, so a stencil could be refused deletion because of a different stencil, and the upgrade page offered the wrong instances. Looking up a stencil whose key existed in two catalogs failed outright. All of these now take the catalog into account; an instance without an explicit catalog belongs to its template's own catalog.
