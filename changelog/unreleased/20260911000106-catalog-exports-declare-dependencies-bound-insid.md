---
type: fix
scopes: [catalogs]
audience: user
title: Catalog exports declare dependencies bound inside stencil content.
---

Export scanned only template models, so a font, theme, or asset that a stencil bound in another catalog never reached `manifest.dependencies` and a re-import was not told the other catalog was required.
