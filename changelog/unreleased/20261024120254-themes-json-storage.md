---
type: feat
scopes: [themes, catalog, storage]
title: Themes are stored as JSON, and a release's themes read like a draft's.
---

A theme of a draft is now stored the way a release keeps it: its content, the theme's wire form, once per tenant by content hash, with one row per draft naming it. A release's themes are read through the same queries as a draft's, so the REST and MCP theme reads take a release number (`catalogVersion = 1.2.0` in MCP) and a release's theme opens read-only at `…/catalogs/{namespace}/{key}@1.2.0/themes/{theme}`. The upgrade to 2.0 converts every theme in the migration step; nothing about a theme changes, and a theme that was ready stays ready. The `themes` table is gone.
