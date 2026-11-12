---
type: feat
scopes: [catalog, themes, editor, api, mcp]
audience: user
breaking: true
title: Each catalog has its own default theme and block style presets; the tenant's default theme becomes the house theme.
---

A template that names no theme renders with its catalog's default theme, which each release keeps. The tenant-wide default theme is now the **house theme**: a new catalog starts with it, and "Apply house theme to all catalogs" sets it everywhere. The upgrade gives every catalog whose templates relied on the tenant default that theme, so nothing renders differently. A catalog also declares the block style presets its templates may use, and the editor says which ones the theme does not style. A theme's own spacing unit now travels with exported and published catalogs, where it used to fall back to 4 points.
