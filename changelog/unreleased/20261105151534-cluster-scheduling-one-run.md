---
type: fix
scopes: [cluster]
title: Scheduled tasks and timers run once at a time across a cluster, and none is left behind by a stuck node.
---

On a cluster of several nodes, a scheduled task or timer could run on two nodes at once. This happened when it waited behind a slow one in a node's batch, when an operator triggered or disabled it mid-run, or when a timer was scheduled again while it ran. A task disabled mid-run lost the completion of that run. A timer whose node was stuck in another timer never ran. Each now runs once at a time. A timer due for longer than `epistola.cluster.timers.owner-grace-ms` (30 seconds) may be picked up by any capable node.
