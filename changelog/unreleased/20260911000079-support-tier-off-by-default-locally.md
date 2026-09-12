---
type: chore
scopes: [config]
audience: dev
title: Support tier off by default locally.
---

The `local` profile no longer enables the commercial support tier, which needs a running epistola-hub and otherwise logs a gRPC `UNAVAILABLE` stack trace on every retry. Start the hub and run with `--epistola.support.enabled=true` when working on Backups, Upgrading or Support → Overview; the local hub host and port are still configured, so that flag is all it takes.
