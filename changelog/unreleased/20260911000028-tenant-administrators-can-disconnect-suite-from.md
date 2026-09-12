---
type: feat
scopes: [exchange]
audience: user
title: Tenant administrators can disconnect Suite from Exchange.
---

The normal action first revokes the remote tenant connection and its refresh credentials, then removes locally stored application credentials, tokens, and pending authorization state. An explicit local-only recovery action remains available when Exchange cannot be reached; applications, publication history, and immutable catalog namespace bindings are retained for later administrator-approved reconnection.
