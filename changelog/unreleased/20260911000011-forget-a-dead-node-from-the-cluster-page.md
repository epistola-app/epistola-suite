---
type: feat
scopes: [cluster]
audience: user
title: Forget a dead node from the Cluster page.
---

Dead rows get a Forget action so an operator does not have to wait for the nightly purge. It is gated on the reconciliation grace period (15 minutes) rather than the 10-second window behind the `stale` badge, re-checking the age inside the delete itself: a node's scheduled-task registrations are written only at startup, so forgetting a node that is merely lagging its heartbeat would strip registrations it cannot restore while running, and the reconciler would then retire any schedule only that node carried. A row therefore reads `stale` before it becomes forgettable. Gated on the new `DIAGNOSTICS_MANAGE` permission so reading the operations pages never implies mutating the registries they show.
