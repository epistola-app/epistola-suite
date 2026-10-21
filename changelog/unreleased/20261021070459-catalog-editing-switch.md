---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: Any catalog can be changed unless the installation is run-only, and receiving a release merges into it.
---

A catalog received from the Exchange, a source or an archive was read-only. Now every catalog has a next version, and a catalog can be changed and released wherever the installation allows editing; only the catalogs Epistola supplies (system, demo) stay read-only. `epistola.catalog.editing.enabled: false` (Helm `catalog.editing.enabled`) makes an installation run-only: it receives, deploys and rolls back releases, and nothing is changed in it, typically production. Receiving a newer release moves an unchanged next version to it; a next version with changes of its own keeps them, and the release can be merged into it. An upgrade from a source is refused while the next version has changes of its own.

The web UI and the MCP server follow the same rule. A catalog that can be changed here offers its drafts, edit controls and Publish wherever its releases come from; a read-only one says why ("Supplied by Epistola", "This installation only runs releases") instead of "Subscribed", and the Authored/Subscribed badges and groupings are gone: the catalog switcher groups catalogs you can change and those that are read-only here. A catalog's version is its highest release, released here or received. Importing a ZIP no longer asks for a type: it installs the release beside the catalog's others, to be merged into a next version with changes of its own. Over MCP, `list_catalogs` replaces `type` with `editable` and `readOnlyReason` and `sourceUrl` with `source`, and code lists, attributes, fonts and images replace `catalogType` with `editable` (`readOnly` stays).
