---
type: refactor
scopes: [mcp]
audience: dev
breaking: true
maturity: alpha
title: The MCP tool list_catalog_versions is now list_catalog_releases.
---

A catalog's versions are its releases (a draft is a release in status draft), as the contract has
called them since 2.0, so the MCP tool that lists them is renamed to `list_catalog_releases`, with
the same parameters and result. There is no alias: MCP is alpha on this line. Internally the Kotlin
types, commands, queries and the `catalog.versions` package follow (`CatalogRelease`,
`ListCatalogReleases`, `DeployCatalogRelease`, `ReleaseCatalogDraft`, …); the database keeps its
`catalog_versions` names. The audit log records
new entries of the renamed commands under their new names (`ReleaseCatalogDraft`,
`DeployCatalogRelease`, …); earlier entries keep theirs.
