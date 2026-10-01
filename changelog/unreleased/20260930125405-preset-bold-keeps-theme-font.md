---
type: fix
scopes: [generation, theming]
audience: user
issues: [1030]
title: A style preset or inline style that sets only bold or italic keeps the theme's font.
---

A block whose preset or inline style set `fontWeight` or `fontStyle` without `fontFamily` rendered in the built-in font (Helvetica or Liberation Sans) instead of the family it inherited from the theme, and a block that set only `fontFamily` lost an inherited bold. The font is now picked once from the whole style cascade. Template versions published from this release render the fix; versions published earlier keep rendering exactly as they did, as their rendering-defaults version is frozen at publish. Drafts and previews show the fix at once.
