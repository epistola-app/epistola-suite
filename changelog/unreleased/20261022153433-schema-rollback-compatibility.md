---
type: feat
scopes: [migrations, deployment]
title: A build refuses to start on a database schema it cannot run against.
---

Every database migration now declares whether code from before it can still run on the schema it
leaves, and the migration step records that in the database. When an older build is started against
a database that a newer one migrated — a rollback — it checks those records first and refuses to
start, with a message naming the migration in the way and the oldest build that can run, instead of
serving from a schema it cannot read. The migration step logs how far back the database can be
rolled back. See the rollback section of `docs/upgrades.md`.
