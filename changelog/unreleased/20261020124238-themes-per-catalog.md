---
type: feat
scopes: [catalog, editor, api, mcp]
audience: user
breaking: true
title: Each catalog has its own default theme and declares the block style presets its templates can use; the tenant's default theme becomes a house theme.
---

A template that names no theme now renders with its catalog's default theme, which each version carries and a published version keeps. Set it on the catalog's Themes page, over REST on a catalog version, or with the MCP tool `set_catalog_theme_settings`. The tenant-wide default theme is no longer used while rendering: it is now the **house theme**, which a new catalog starts with and which "Apply house theme to all catalogs" sets everywhere. Upgrading gives every catalog whose templates relied on the tenant default that theme as its own default, so nothing renders differently.

A catalog also declares its block style presets (key, name and description), and each theme gives them their look. The editor offers the catalog's presets, together with those of the catalogs it depends on, and says which ones the theme does not style; once a catalog declares presets, saving a block that names another one is refused, and the quality ledger reports a block whose preset its theme does not style. Both settings travel with exported and imported catalogs.
