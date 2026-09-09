<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Upgrading an installation

How to move a running installation to a new version, and what to check before you do.

Most upgrades are uneventful: migrations are additive, the `pre-upgrade` Job applies them, and the
rolling update replaces pods without anyone noticing. This page is about telling those apart from
the ones that need a window, and running the second kind safely.

For how migrations are wired (`migration.mode`, the Job, the init container), see
[`deployment.md`](deployment.md#database-migrations-migrationmode). For how migrations are authored,
see [`migrations.md`](migrations.md).

## PostgreSQL 18 is required

From the catalog-resource-identity release onward the suite needs **PostgreSQL 18 or later**;
identities are minted with `uuidv7()`, added in 18. The first migration checks the server version
and stops with

```
ERROR:  Epistola requires PostgreSQL 18 or later from this release; this server is 17.5
```

before any DDL runs, so an older server costs you a failed migration rather than a half-applied
one. Upgrade the database first, then the suite — and note that a major PostgreSQL upgrade is its
own maintenance window on top of the one below.

Check what you are on:

```bash
psql "$DATABASE_URL" -tAc "SHOW server_version;"
```

## The two kinds of upgrade

**Additive.** The migration only adds — a column, a table, an index, a seeded row. Old application
code never notices, so the `pre-upgrade` Job can run while the previous version is still serving and
the rolling update proceeds normally. No window needed.

**Breaking.** The migration removes or reshapes something old code still reads — a dropped column, a
renamed one, a changed primary key. Here the default ordering works against you:

1. The `pre-upgrade` hook Job commits the new schema.
2. _Only then_ is the Deployment updated.
3. Kubernetes replaces pods 25% at a time (the chart declares no `strategy:`, so this is the
   default), and there is no PodDisruptionBudget.

Between steps 1 and 3, **every replica still serving traffic is running code that cannot read the
schema underneath it**. During step 1 those same pods are also blocked on the `ACCESS EXCLUSIVE`
locks the DDL takes, so their connections time out and `/readyz` starts failing.

A breaking upgrade therefore needs a maintenance window. It is not a defect in the chart — it is
what "breaking" means. The release notes say which kind you are getting.

## Running a breaking upgrade

```bash
# 1. Stop the application. Nothing may hold the old schema open.
kubectl scale deployment/epistola --replicas=0
kubectl rollout status deployment/epistola --timeout=5m

# 2. Confirm nothing is still alive. Every replica heartbeats every 2s, so a row
#    seen within the last minute means a node is still running.
psql "$DATABASE_URL" -c \
  "SELECT node_id, version, last_seen_at FROM cluster_nodes
    WHERE last_seen_at > now() - interval '1 minute';"
#    Expect zero rows before continuing.

# 3. Upgrade. The pre-upgrade Job migrates, then the Deployment rolls back up.
helm upgrade --install epistola epistola/epistola --version <new> -f values.yaml
```

Step 2 is manual today. [#906](https://github.com/epistola-app/epistola-suite/issues/906) tracks
making the migration refuse to start while a node is live, so the precondition is enforced rather
than remembered.

### If the migration is large

Migration time scales with the data a migration touches, and the Job is bounded by
`migration.job.activeDeadlineSeconds` (default `600`). If it is exceeded the pod is killed, Postgres
rolls the transaction back — no partial state — and `backoffLimit` retries pay the full cost again
before the release fails.

Before a large upgrade, rehearse it on a **restored production snapshot** rather than on an empty
database, and time it. The failure mode that fixtures cannot reproduce is rows whose data does not
match what the migration assumes. Raise `activeDeadlineSeconds` past the measured time with margin.

`migrate` mode relaxes the JDBC socket timeout and leak detector for the migration JVM, since long
DDL reads nothing from the socket for minutes at a stretch. **`embedded` mode does not** — it uses
the application's own connection settings, which are tuned for request work. Use `migrate` (the
chart's `job` or `initContainer` modes, or the standalone container in
[`deployment.md`](deployment.md#run-the-migration-step-standalone-outside-helm--ci--one-off)) for
anything substantial.

## Backups and upgrades

**A tenant backup can only be restored by a build whose schema is compatible with the one the backup
was taken on.** Each migration declares this in a
[`backup-restore-compatibility` header](tenant-backup.md#schema-compatibility); a migration that
changes a backed-up table's columns declares `backward=false`, and every backup taken before it
becomes unrestorable the moment it commits. There is no override.

Practically:

- **Take and verify a backup before upgrading** — a database-level one, not a tenant backup. Tenant
  backups from before a breaking upgrade will not restore into the new build, so they are not a
  rollback path.
- **After upgrading, run a fresh tenant backup** rather than waiting for the daily schedule.
  Until it completes, the Backups page shows every existing backup as `Older version` with restore
  disabled.
- Rolling the application back does not help: `forward=false` means backups taken on the new schema
  cannot be restored into the old build either.

## Rollback

There is none at the schema level. Migrations are forward-only and the application never resets a
database ([`migrations.md`](migrations.md)). Rolling the image back leaves the new schema in place,
which the old code cannot read.

The rollback path for a breaking upgrade is therefore: restore the database from the backup taken in
the step above, then redeploy the old image. Which is why that step is not optional.

## Checklist

- [ ] Read the release notes; establish whether the upgrade is additive or breaking.
- [ ] Take a database-level backup and verify it restores.
- [ ] For a breaking or large upgrade, rehearse on a restored snapshot and time the migration.
- [ ] Raise `migration.job.activeDeadlineSeconds` past the measured time if needed.
- [ ] Scale to zero and confirm `cluster_nodes` shows no recent heartbeat.
- [ ] Upgrade.
- [ ] Run a fresh tenant backup once pods are healthy.
