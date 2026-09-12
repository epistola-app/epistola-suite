---
type: fix
scopes: [exchange]
audience: user
title: Connect and reauthorize now navigate to Exchange normally.
---

The authorization forms bypass HTMX so the browser follows the cross-origin redirect as a top-level navigation instead of attempting a CSP-blocked background request.
