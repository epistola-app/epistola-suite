---
type: fix
scopes: [exchange]
audience: user
title: A submission Exchange never decides is now given up on.
---

Following one spends no retry budget, because nothing has failed, which left it the one wait with no end: a submission Exchange took but never resolved was polled every thirty seconds indefinitely while holding its retained release archive, visible only as a queue age climbing for no stated reason. It is now followed for `epistola.exchange.submitted-timeout` (24 hours by default) and then failed with that reason recorded, keeping the archive so it can be retried or withdrawn. A state Suite does not recognize still counts as in flight, so a new Exchange state cannot break an older Suite.
