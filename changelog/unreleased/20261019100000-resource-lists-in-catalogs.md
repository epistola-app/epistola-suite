---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: Every list of templates, themes, stencils, images, fonts, code lists or attributes belongs to a catalog.
---

A list is at `/tenants/{tenant}/catalogs/{catalog}/templates` (and `…/themes`, `…/stencils`, `…/images`, `…/fonts`, `…/code-lists`, `…/attributes`); long-term work keeps its own segment, as in `…/catalogs/letters@<draft>/templates`. The lists across every catalog of a tenant, and their catalog picker, are gone: `/tenants/{tenant}/themes` and the like, with or without `?catalog=`, are no longer pages, so update bookmarks to the catalog's list. Within a catalog the template list no longer shows a catalog column. Pages that used to land on a list across catalogs, after deleting a template for instance, land on the catalog's list.
