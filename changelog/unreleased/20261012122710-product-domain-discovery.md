---
type: feat
scopes: [product-domain, version-check, exchange, support]
breaking: true
title: Epistola services are found through one product domain, with one endpoint setting each.
---

`epistola.product-domain` (Helm `productDomain`, default `epistola.app`) is now the only setting needed to point an installation at another Epistola site; the version check, installation check, Exchange and support hub look up their addresses there and fall back to the previous ones. **Action on upgrade** only if you set one of the old URL settings: rename it to the new `endpoint` (`version-check.endpoint`, `exchange.endpoint`, `support.hub.endpoint`). The application refuses to start until you do, and names the replacement.
