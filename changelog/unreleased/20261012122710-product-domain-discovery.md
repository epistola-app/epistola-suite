---
type: feat
scopes: [product-domain, version-check, exchange, support]
audience: user
breaking: true
title: Epistola services are found through one product domain, and each has one endpoint setting.
---

`epistola.product-domain` (Helm `productDomain`, default `epistola.app`) is now the only setting needed to point an installation at another Epistola site, such as staging. The version check, installation check, Exchange and support hub read their addresses from `https://{product-domain}/.well-known/epistola.json`, fetched only when needed, and fall back to their previous addresses when it is unavailable. To set one yourself, use its `endpoint` instead of the old settings: `version-check.endpoint` replaces `well-known-url`, `exchange.endpoint` replaces `base-url` and `discovery-url`, and `support.hub.endpoint` (`host:port`) replaces `host`, `port` and `discovery-url`. **Action on upgrade** only if you set one of the old settings: the application refuses to start until it is renamed, and says which. See [Data shared with Epistola](docs/outbound-traffic.md).
