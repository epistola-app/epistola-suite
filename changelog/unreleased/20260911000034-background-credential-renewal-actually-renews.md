---
type: fix
scopes: [exchange]
audience: dev
title: Background credential renewal actually renews.
---

The sweep selected connections expiring within five minutes and then declined to refresh anything with more than thirty seconds left, so tokens were only ever renewed at the wire; it also compared an application-clock expiry against the database clock. Both are fixed.
