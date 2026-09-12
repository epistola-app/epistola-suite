---
type: fix
scopes: [exchange]
audience: dev
title: Every Exchange call is bounded and made outside a database transaction.
---

Connect and read timeouts are configurable (`epistola.exchange.connect-timeout`, `read-timeout`), and token exchange, refresh and revocation no longer run while holding a pooled connection or a row lock. Token refresh writes the rotated pair back under an optimistic version check. The OAuth endpoints advertised by the issuer are stored on the connection, so refreshes never reconstruct a hard-coded path.
