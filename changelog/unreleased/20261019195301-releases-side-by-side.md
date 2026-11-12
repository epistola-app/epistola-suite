---
type: feat
scopes: [catalog, exchange, api, mcp]
audience: user
breaking: true
title: Any catalog can be changed unless the installation is run-only, and received releases install side by side.
---

A catalog received from Exchange, a source or an archive was read-only and was upgraded in place. Now every catalog has a next release and can be changed here, except the ones Epistola supplies; `epistola.catalog.editing.enabled: false` (Helm `catalog.editing.enabled`) makes an installation run-only, typically production. A received catalog keeps every release it installed: **Available releases** lists what its source offers, older lines included, and **Install** adds one beside the others, so deployments keep what they use. A newer release moves an unchanged next release along. The Authored and Subscribed labels are gone. Over REST `listAvailableCatalogReleases` and `installCatalogRelease` replace the upgrade operations, and `CatalogDto` says `editable` and `readOnlyReason` instead of `type`.
