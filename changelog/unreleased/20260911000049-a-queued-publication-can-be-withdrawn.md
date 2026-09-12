---
type: feat
scopes: [exchange]
audience: user
title: A queued publication can be withdrawn.
---

Queueing the wrong release previously had no way out but waiting for Exchange, exhausting the retries, or disconnecting the tenant. Withdrawing releases the retained archive, leaves the attempt in the history, and lets the release be queued again while the working copy still matches. A publication Exchange is already holding cannot be withdrawn — that would abandon the outcome rather than prevent it.
