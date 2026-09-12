---
type: fix
scopes: [exchange]
audience: user
title: Credentials Exchange refuses now explain what to do about them.
---

The Exchange page reported `401 Unauthorized: "{"error":"invalid_client"}"` — the transport's own words, stored verbatim and shown as the entire explanation. It now says whether the connection simply needs reconnecting, or whether Exchange no longer recognises the installation's application at all, in which case reconnecting is not enough and its credentials have to be reissued during authorization. Credentials Exchange will not accept also stop being treated as a failure of whichever publication happened to notice: that publication waits instead of spending a retry on something no amount of retrying fixes.
