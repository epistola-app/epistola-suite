---
type: feat
scopes: [exchange]
audience: user
maturity: alpha
title: Publish catalog releases to Epistola Exchange.
---

A tenant connects to Epistola Exchange through a guided, recoverable OAuth flow and publishes its catalog releases there. A release succeeds whether or not Exchange is reachable: a cluster-safe background worker submits it, keeps the archive until Exchange accepts or rejects it, and renews the connection's credentials. A catalog's Exchange namespace is always chosen explicitly, publishing is its own permission, and the publish controls appear only once a release could actually reach Exchange. The Exchange page shows publication activity across catalogs and lets a queued publication be withdrawn. A release names the other catalogs it depends on rather than carrying them. Alpha, off by default: it needs `epistola.exchange.enabled` for the deployment and the `catalog-publishing` toggle for the tenant.
