---
type: fix
scopes: [db]
audience: dev
title: A migration launched outside Helm keeps its own timeouts.
---

The migration JVM inherited the application's 30-second socket timeout and 60-second leak detector, which are tuned for request work and abort long DDL. The Helm chart relaxed both for its Job, but a migration started any other way — the documented standalone container, a CI gate — got no such help. `MigrationLauncher` now relaxes them itself.
