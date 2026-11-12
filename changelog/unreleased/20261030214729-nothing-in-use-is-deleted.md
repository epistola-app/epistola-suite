---
type: fix
scopes: [catalog, api]
audience: user
breaking: true
title: Nothing that is still in use can be deleted.
---

Deleting a stencil, font, image, theme, release or catalog that something still used could be forced or went unchecked, leaving a draft naming something that was gone, found only when publishing failed. Each delete is now refused, naming what uses it, for every kind of use: a theme's fonts, a variant's attributes, a stencil inside a stencil, another catalog's drafts. **Used by** shows the same uses. The REST image delete still accepts `force`, but ignores it. The default catalog can now be deleted like any other; only the system catalog cannot, and it is no longer offered for export.
