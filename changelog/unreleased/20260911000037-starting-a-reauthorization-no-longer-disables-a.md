---
type: fix
scopes: [exchange]
audience: user
title: Starting a reauthorization no longer disables a working connection.
---

The connection stayed usable only if the browser flow completed; abandoning it left valid credentials behind a connection that looked unenrolled and silently stalled publishing. It now keeps working until a new authorization actually completes.
