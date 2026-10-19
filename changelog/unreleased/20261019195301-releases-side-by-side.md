---
type: feat
scopes: [catalog, exchange, api, mcp]
audience: user
breaking: true
title: A subscribed catalog installs the releases its source offers side by side; nothing is upgraded in place.
---

In 1.x upgrading a subscribed catalog replaced what was installed with its source's latest release. In 2.0 a subscribed catalog keeps every release it installed, and **Available releases** lists every release its source offers that you do not have, older lines included: a fix to 1.4 is listed while you have 2.0. Each shows what it changed against the closest release you have below it and which releases of other catalogs it uses. **Install** adds it beside the others, after installing the releases it uses that you lack; nothing already installed is removed or changed, so every deployment and catalog that uses one keeps using it. Which release an environment serves is decided by deploying it. Over REST this is `listAvailableCatalogReleases` and `installCatalogRelease`, replacing `previewCatalogUpgrade` and `upgradeCatalog`; over MCP `list_available_catalog_releases` replaces `preview_catalog_upgrade`.
