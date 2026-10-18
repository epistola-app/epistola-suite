---
type: feat
scopes: [catalog, api, mcp]
title: Long-term work can be made the next version, and exports can carry the catalogs they use.
---

When long-term work is ready, **Make this the next version** turns it into where everyday work goes, after confirming each version published since it started; the old next version is kept under a name or discarded. Long-term work itself is not published: it is tried by pointing a test environment at it, and published once it is the next version.

**Export with what it uses** writes one file with the release and the versions of the catalogs it uses, transitively, so it installs where they are not, such as an acceptance or production installation without Exchange. Importing it installs what is missing first and keeps what is there; it is refused, naming each, when it would move a catalog the installation has at another version.

REST: `promoteCatalogDraft`, and `importCatalog` accepting a bundle (`installedDependencies`, `presentDependencies`). MCP: `promote_long_term_work`.
