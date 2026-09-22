---
type: feat
scopes: [exchange, catalog, ui]
audience: user
maturity: alpha
title: Browse Epistola Exchange and install catalogs from it.
---

A page beside Subscribe and Import ZIP: search, pick a version, install. The dialog says what matters before deciding — the catalog arrives as a read-only mirror, a failed resource abandons the whole install, and whether the catalog ID is already taken. Only releases this tenant can install are offered. An installed catalog links back to its Exchange listing, and a newer release is noticed in the background and shown from any page. An upgrade installs the new release as a whole, so a resource the publisher moved or removed disappears, under the usual in-use checks, rather than following it to its new catalog. Alpha, off by default: it needs `epistola.exchange.enabled`, the `catalog-installing` toggle and a connection to Exchange.
