---
type: fix
scopes: [catalog]
title: Catalogs are released and exported as the stricter 2.0 wire format requires.
---

A release names its own fingerprint and pins a release of every catalog it declares; an export of a
working copy pins each declaration to the release it resolves to now. A reference to the catalog
itself names no catalog: a theme now stores a font of its own catalog relatively, as templates and
stencils already did, and an image of the catalog itself travels relatively in a release. The
bundled system catalog is 2.1.1 and the demo catalog 6.8.0, rewritten to the format without
changing what they render.
