---
type: fix
scopes: [catalog, ui, mcp]
audience: user
title: A draft whose only change is a dependency, its default theme, presets or license can be published.
---

The next release's changes compared resources only, so after adding a dependency to a catalog whose resources were all published, its page said "Everything is published" and offered no Publish. A draft now has changes when its content differs from the release it is based on, which includes the catalogs it uses, its default theme, its presets and its license; its name, description and keywords are not content and still change nothing. The draft's page and the publish dialog list them in words ("Added: Shared (Dependency) — locked to v1.0.0", "Changed: Default theme (Catalog setting)"), without a ready mark to wait on, and MCP `get_catalog_changes` returns them as `settings`. A preset added now suggests a minor release and one removed a major; another default theme or license is a minor release too, since it changes what every user of the catalog renders with or is licensed under.
