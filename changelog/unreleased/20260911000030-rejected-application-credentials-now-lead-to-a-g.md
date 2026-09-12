---
type: fix
scopes: [exchange]
audience: user
title: Rejected application credentials now lead to a guided recovery flow.
---

Suite records that reauthorization is required, discards the failed one-time authorization, and tells the administrator to rotate the selected Exchange application's credentials instead of showing a generic unexpected-error page.
