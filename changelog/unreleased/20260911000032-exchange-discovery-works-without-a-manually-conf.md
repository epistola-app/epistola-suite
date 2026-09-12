---
type: fix
scopes: [exchange]
audience: user
title: Exchange discovery works without a manually configured base URL.
---

`epistola.exchange.base-url` ships blank as the signal to use the public discovery document, but a valueless YAML key binds to an empty string rather than nothing, so the escape hatch read as configured and every deployment that had not set it explicitly failed to connect. Blank optional URLs are now treated as unset. The `local` profile sets the base URL, which is why the local flow always worked and the production path was never exercised.
