---
type: feat
scopes: [catalogs]
audience: user
title: Every catalog resource can now be moved between catalogs.
---

Themes were the last type and the most connected: referenced from content (`themeRef`), from a template's own binding, and from the tenant-wide default. The relational two follow by `ON UPDATE CASCADE`; content references are rewritten in drafts and resolved through the alias in published versions. `ThemeStyleResolver` follows the alias, so a template that names the theme's old catalog renders in that theme instead of quietly falling back to the tenant default. `unsupported-resource-type` now has no subject left, and a test asserts the set is complete rather than the blocker merely being unused.
