---
type: feat
scopes: [mcp, themes]
title: update_theme can add, replace or remove single block style presets.
---

`blockStylePresets` replaces a theme's whole preset map, so adding one preset over MCP meant
resending every other one. `update_theme` now also takes `mergeBlockStylePresets` (presets to add or
replace by name) and `removeBlockStylePresets` (names to drop). The change is applied in one database
statement, so it cannot overwrite a concurrent edit to another preset.
