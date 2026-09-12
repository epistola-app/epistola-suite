---
type: fix
scopes: [exchange]
audience: user
title: Exchange must be reached over HTTPS.
---

The client secret, refresh token and full catalog archive cross this connection, so a plaintext discovery document, base URL or token endpoint is refused. `epistola.exchange.allow-http` opts a local Exchange checkout out, matching `epistola.catalog.allow-http`; the `local` profile sets it and no other profile does.
