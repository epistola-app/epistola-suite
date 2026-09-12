---
type: feat
scopes: [catalogs, fonts]
audience: user
title: Assets and fonts can be moved between catalogs.
---

Both are resolved while rendering, by the address the content names, so a move risked what no earlier movable type could: a published document that renders _successfully_ but wrongly — a missing image, or silently falling back to the built-in typeface. `GetAssetContent` and `ResolveFontFace` now follow the alias when the address they are given no longer holds the resource.
