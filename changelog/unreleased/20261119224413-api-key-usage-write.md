---
type: perf
scopes: [security, api]
title: Concurrent API requests on one key no longer wait for each other to record the key's use.
---

Every API request wrote its key's "last used" time, on the request thread. Concurrent requests with
the same key queued on that write, and under load it was most of the database time of a request. The
time is now written at most once a minute per key, the precision the application page shows it at.
