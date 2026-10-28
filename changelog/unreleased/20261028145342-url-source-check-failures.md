---
type: fix
scopes: [catalog]
title: A catalog's source that is down shows as unreachable, not as answering with something unusable.
---

The scheduled check of a catalog subscribed from a URL recorded a server error (5xx), a "slow down"
(429), a 410 and any other refusal as a protocol error, and logged each as a bug in the check. A
server error or 429 now shows as "could not be reached" and a 410 as "no longer publishes this
catalog"; other refusals stay a protocol error, without the warning in the log.
