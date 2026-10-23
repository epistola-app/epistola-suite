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

Each migration declares which kind it is, in a
[`rollback-compatible` header](migrations.md#rollback-compatibility). A release is additive exactly
when every migration it adds declares `true` — the same property that decides whether you can roll
back afterwards (see [Rollback](#rollback)).

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

Migrations are forward-only and never undone ([`migrations.md`](migrations.md)). Rolling the image
back leaves the newer schema in place, so a rollback works only when the older code can run on it —
and each migration declares whether it can ([rollback compatibility](migrations.md#rollback-compatibility)).

Every build checks this at startup, before it touches the schema. When the database holds migrations
the build does not ship, each must be recorded as rollback-compatible. If one is not, the build
refuses to start, in every migration mode — the app pod, the migration Job and the init container
all exit non-zero, and the log leads with the way back:

```text
***************************
APPLICATION FAILED TO START
***************************

Description:

Database schema is ahead of this build, which cannot run against it. A newer release migrated the
database with migration(s) this build does not ship and that are not rollback-compatible:
20261103091500 (not rollback-compatible, recorded by 1.7.0). ...

Action:

To go back to this build, restore a PostgreSQL backup of the database taken before that upgrade,
then start this build again; data written since the backup is lost. To keep that data, deploy a
build that ships migration 20261103091500 or later instead ...
```

So a rollback either works or fails fast; old code never serves from a schema it cannot read. When
every migration in between is compatible, the older build starts and logs a warning that it is
running on a newer schema. Under Helm, `helm rollback` does not run the `pre-upgrade` migration Job,
so a refused rollback shows as the rolled-back pods crash-looping on that message while the pods
still on the newer version keep serving.

To know in advance how far back you can go, look at the **rollback floor** — the newest applied
migration that is not rollback-compatible. The migration step logs it on every run
(`Rollback floor: a build must ship migration … (recorded by 1.7.0) …`), or ask the database:

```sql
SELECT version, recorded_by
  FROM flyway_rollback_compatibility
 WHERE NOT rollback_compatible
 ORDER BY version DESC
 LIMIT 1;
```

Any build that ships that migration can run on the database: the release that recorded it, or a
later one.

Each release's notes say the same before you install it: "can be rolled back to vA.B.C or later".
At release time it comes from the repository — the newest migration that is not rollback-compatible,
then the oldest release tag that ships it:

```bash
floor=$(grep -L -x -e '-- rollback-compatible: true' modules/*/src/main/resources/db/migration/*/V*.sql \
  | xargs -n1 basename | sort | tail -1)
for t in $(git tag -l 'v[0-9]*' --sort=v:refname | grep -v -e '-'); do
  git ls-tree -r --name-only "$t" -- modules | grep -q "/$floor\$" && { echo "$t"; break; }
done
```

No tag means the floor is a migration the release itself adds: it cannot be rolled back at all, and
its notes say so as a breaking upgrade.

Releases from before the check (those without migration `V20261022152421`) do not perform it, so a
rollback to one of them is not guarded. When a rollback is refused, or the target predates the
check, the path is as before: restore the database from the backup taken before the upgrade, then
deploy the old image. Which is why that backup is not optional.

## Checklist

- [ ] Read the release notes; establish whether the upgrade is additive or breaking, and so whether
      you can roll back afterwards.
- [ ] Take a database-level backup and verify it restores.
- [ ] For a breaking or large upgrade, rehearse on a restored snapshot and time the migration.
- [ ] Raise `migration.job.activeDeadlineSeconds` past the measured time if needed.
- [ ] Scale to zero and confirm `cluster_nodes` shows no recent heartbeat.
- [ ] Upgrade.
- [ ] Run a fresh tenant backup once pods are healthy.
