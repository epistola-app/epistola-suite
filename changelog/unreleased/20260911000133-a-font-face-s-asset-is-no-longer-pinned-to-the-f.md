---
type: fix
scopes: [fonts]
audience: dev
title: A font face's asset is no longer pinned to the font's catalog.
---

`font_variants.catalog_key` backed two foreign keys at once — the family's catalog and the backing asset's — so one column could not follow two parents and moving either resource would have dragged the other's reference along. The asset's catalog is now its own column, which also lifts an undocumented restriction that a face's asset had to live in the font's catalog.
