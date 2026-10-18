---
type: feat
scopes: [catalog, api, mcp]
title: Long-term work can be tried as a pre-release and made the next version, and exports can carry the catalogs they use.
---

Long-term work is published as a **pre-release**, numbered from its changes and labelled after its name (`2.0.0-redesign-2027.1`), to try it on a test environment or another installation. A pre-release is never the latest version: no version range picks it, a request without an environment never gets it, the catalog's published version does not move, and it is never sent to Epistola Exchange; an environment it is deployed to serves it. When the work is ready, **Make this the next version** turns it into where everyday work goes, after confirming each version published since it started; the old next version is kept under a name or discarded.

**Export with what it uses** writes one file with the release and the versions of the catalogs it uses, transitively, so it installs where they are not, such as an acceptance or production installation without Exchange. Importing it installs what is missing first and keeps what is there; it is refused, naming each, when it would move a catalog the installation has at another version.

REST: `promoteCatalogDraft`, `releaseCatalogDraft` on long-term work, pre-release numbers in addresses, `CatalogVersionDto.preRelease`, and `importCatalog` accepting a bundle (`installedDependencies`, `presentDependencies`). MCP: `release_long_term_work`, `promote_long_term_work`.
