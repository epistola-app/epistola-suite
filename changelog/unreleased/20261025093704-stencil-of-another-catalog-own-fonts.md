---
type: fix
scopes: [stencils, generation]
title: A stencil of another catalog renders with its own catalog's fonts from a release.
---

When a template used a stencil of another catalog, and that stencil styled its text with a font of its own catalog, documents rendered from a release drew that text in the default font: the release names the stencil's own-catalog references relatively, and they were looked up in the template's catalog. They now mean the stencil's catalog wherever the stencil is used, as previews of a draft already did.
