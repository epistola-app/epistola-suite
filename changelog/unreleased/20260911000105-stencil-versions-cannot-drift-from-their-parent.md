---
type: fix
scopes: [catalogs]
audience: dev
title: Stencil versions cannot drift from their parent's address.
---

A relocation-era migration left `stencil_versions.catalog_key` and `stencil_key` unconstrained while ten queries still filtered on them; the address foreign key is restored with `ON UPDATE CASCADE`.
