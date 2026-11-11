---
type: fix
scopes: [catalog, ui]
audience: user
title: A catalog's list pages show what its dependencies hold at the version it pins.
---

The "From catalogs it depends on" section on a catalog's stencils, images, fonts, themes, attributes
and code lists pages listed each dependency's next version, so it could show resources the catalog
cannot use and miss ones it does, for example after a suite upgrade brought a newer system catalog.
It now lists each dependency at the version the catalog pins, as the editor and rendering use it,
and no longer stops at 50 rows. The templates page no longer shows the section: another catalog's
templates are not something a catalog uses.
