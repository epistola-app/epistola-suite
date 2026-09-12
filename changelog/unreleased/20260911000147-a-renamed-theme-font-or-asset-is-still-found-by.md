---
type: fix
scopes: [catalogs]
audience: user
title: A renamed theme, font or asset is still found by content naming its old address.
---

Relocation renames as well as moves, but the three render-time alias fallbacks took the canonical _catalog_ while keeping the _requested key_, so only a catalog change resolved. None of the failures was loud — a renamed theme fell back to the tenant default, a font to the built-in typeface, an image simply vanished — and the tests only ever exercised moves, which is why they passed.
