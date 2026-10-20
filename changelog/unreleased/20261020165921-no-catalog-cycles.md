---
type: feat
scopes: [catalog]
audience: user
title: Catalogs can no longer come to use each other.
---

A change that would make a catalog use a catalog that already uses it, directly or through other catalogs, is now refused as it is made, on every surface: using another catalog's theme, stencil, code list, image or font, declaring a dependency, importing an archive or moving a resource. The message names the catalogs and the way round, for example `'letters' uses 'shared' uses 'base'`. Before, such catalogs could be built but never released. Move what two catalogs share into one of them, or into a catalog they both use.
