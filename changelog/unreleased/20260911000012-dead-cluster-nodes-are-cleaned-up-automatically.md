---
type: feat
scopes: [cluster]
audience: user
title: Dead cluster nodes are cleaned up automatically.
---

A node id is the pod hostname, so every rollout left a permanent row in the registry — an installation could show dozens of stale nodes going back weeks on the Cluster page, each also leaking a scheduled-task registration per definition. A daily `single_owner` task now purges nodes unseen for `epistola.cluster.node-reaper.stale-node-retention` (default 7 days) together with the registration and per-node task-state rows they orphaned. Retention is deliberately measured in days and clamped to at least 4x the reconciliation grace period: a node row is what vouches for that node's scheduled-task definitions, so purging too eagerly would turn a routine pod restart into lost schedules. The current node is never purged.
