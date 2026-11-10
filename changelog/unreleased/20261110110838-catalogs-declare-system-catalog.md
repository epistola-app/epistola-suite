---
type: feat
scopes: [catalog, editor]
audience: user
title: A new catalog uses the system catalog from the start, and pickers offer only the catalogs a catalog declares.
---

A new catalog, and a tenant's default catalog, now declares the system catalog at its highest
release when it is created, so its fonts, locales and themes are there to use at once. Existing
drafts get the same declaration when you upgrade; releases are unchanged.

The template theme, variant attribute, editor font and catalog default-theme pickers now offer a
catalog's own resources and those of the catalogs it declares, read at the release each declaration
pins. Before, the template theme and attribute pickers offered every catalog of the tenant, and the editor offered system fonts from the
system catalog's newest release rather than the one the catalog renders with. To use another
catalog's resources, declare it under the catalog's dependencies.
