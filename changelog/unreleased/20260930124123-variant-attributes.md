---
type: fix
scopes: [templates, catalog]
audience: user
issues: [1021, 1022, 1025]
title: Variant attributes are checked and matched correctly across catalogs.
---

Choosing a variant by attributes now looks in the template's own catalog rather than `default`, which could give a 404 or the wrong language. Two variants of one template can no longer share an attribute set. An attribute still used by a variant in any catalog can no longer be deleted or narrowed.
