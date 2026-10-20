---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: A template, theme, stencil, image, font, code list or attribute is created in its catalog.
---

The create dialogs open at `/tenants/{tenant}/catalogs/{catalog}/templates/new` (and likewise for the other kinds) and create there; `/tenants/{tenant}/templates/new?catalog=…` and the posts to `/tenants/{tenant}/templates` are gone. The tenant home's "New Template" and "New Theme" shortcuts are removed: open a catalog to create in it.
