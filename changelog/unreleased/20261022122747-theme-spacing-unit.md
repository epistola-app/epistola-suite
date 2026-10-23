---
type: fix
scopes: [catalog, themes]
title: A theme's spacing unit is kept when its catalog is exported, imported or released.
---

A theme can set its own spacing unit, the base of its spacing scale. Exporting a catalog left it out, so a theme imported into another installation fell back to the default of 4 points, and a published version did the same, so documents rendered from it were spaced differently from the theme. The spacing unit now travels with the theme everywhere, including the first published versions the upgrade from 1.x cuts.
