---
type: feat
scopes: [migrations, deployment]
title: A build refuses to start on a database schema it cannot run against.
---

Every migration now records whether code from before it can still run on the schema it leaves. When an older build is started against a database a newer one migrated, it refuses to start and names the migration in the way and the oldest build that can run, instead of serving from a schema it cannot read. The migration step logs how far back the database can be rolled back (`docs/upgrades.md`, "Rollback"). An operator who has checked that a refused rollback is safe can set `epistola.migration.rollback-guard.enabled=false` (Helm `migration.rollbackGuard.enabled`).
