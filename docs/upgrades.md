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
   default). A PodDisruptionBudget (`podDisruptionBudget.enabled`) does not change this: it limits
   evictions, not a rolling update.

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

# 4. With the HPA on (autoscaling.hpa.enabled), the chart leaves `replicas` to the HPA, and an HPA
#    does not scale a Deployment up from zero: scale it back up yourself.
kubectl scale deployment/epistola --replicas=<autoscaling.hpa.minReplicas>
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

## From 1.x to 2.0

2.0 renders catalog releases deployed to environments, where 1.x rendered template versions
activated per environment. What each author was working on becomes the working copy, and what was
last published becomes each catalog's first 2.0 release. The 1.x template, data contract and stencil
versions and the environment activations are then removed; a summary of them (every version's
number, status and dates, and what each environment had activated) is kept as the `app_metadata`
record `upgrade-1x-history`, so a document or load-test run that names a 1.x version number can still
be traced. The content of older published versions is not kept. No 1.x release kept its content
either, so the upgrade cannot deploy anything for you:

**The upgrade checks first whether it can finish.** Before a single migration runs, the migration
step (and the app in `embedded` mode) runs the upgrade's preflight checks. Each one that finds a
problem is reported with the tenant, the catalogs and every resource involved, and the step exits
non-zero. The database is untouched and 1.x keeps working: fix it there and run the upgrade again.
For this upgrade:

| Check                          | Refuses when                                                                                                                      |
| ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------- |
| `1x-catalog-dependency-cycles` | authored catalogs use each other, which 2.0 cannot release; move what they share into one of them, or into a catalog they all use |
| `1x-missing-catalogs`          | a catalog uses a catalog the tenant does not have; install it, or change what uses it                                             |

Both read what the upgrade keeps — each variant's and stencil's latest published version and draft,
themes, attributes, templates and the catalogs' details — and only authored catalogs' references:
a subscribed catalog's release is recorded as installed. A check that cannot run refuses too. To
go ahead regardless, once you have read why, skip a check by id:
`EPISTOLA_MIGRATION_PREFLIGHT_SKIP=1x-missing-catalogs` (comma-separated; in the Helm chart under
`config.env`). A catalog a skipped check would have reported stays without a first release.

**Documents can look different after the upgrade.** Two 1.x choices are not carried over, so check
the documents of each environment before deploying to it:

- **An environment that ran an older version** gets what was last published once the first release
  is deployed: production on a template's version 1 while version 2 went to test serves version 2.
  The older content is not kept, so it cannot be deployed instead. Which version each environment
  had activated is in `upgrade-1x-history`.
- **A stencil instance pinned to an older stencil version** renders the stencil as it is now. An
  instance whose copy differed from the version it pinned keeps that copy as plain content and stops
  following the stencil. Each case is listed in the `app_metadata` record
  `upgrade-2.0-template-content` and in the log, prefixed `2.0 upgrade:`. One case is kept as it
  rendered: a parameter a template binds that the stencil's latest version no longer declares
  (for example because an API update dropped the stencil's parameters) would render empty, so the
  upgrade declares it again on the stencil, optional, and reports it. Remove it once no template
  binds it.

**Generation stops at the upgrade and resumes per environment as you deploy.** Plan the upgrade as
that outage, and tell the teams whose applications request documents.

1. **Drain the generation queue** before scaling down: a request still queued at the upgrade named
   a template version, which 2.0 no longer renders, and fails.
2. **Upgrade** as a breaking upgrade (above), and move REST clients to contract 2.0.
3. **Wait for the first releases.** At the first start a background task gives every authored
   catalog its dependency declarations and a first release, cut from what was last published before
   the upgrade and numbered the patch after the catalog's highest 1.x release (or 1.0.0), and every
   subscribed catalog its installed release as one it can render from. The 1.x releases themselves
   are removed: they kept only a number, a fingerprint and notes, so nothing could render, export or
   deploy them, and their Exchange publication records go with them (see
   [`catalog-versioning.md`](catalog-versioning.md#declared-dependencies)). It runs at startup and
   then every five minutes (`epistola.catalog.upgrade.interval-ms`) until the queue is empty. Follow
   it in:
   - the **tenant home**, which says how many catalogs are still being prepared and which
     environments make no documents yet;
   - the **log**: each catalog it releases, each it cannot release yet with the reason, and
     `2.0 upgrade finished` when the queue is empty;
   - the metric **`epistola_catalog_upgrade_queued`**: catalogs still waiting (0 when done, -1
     before this node's first run);
   - the table, for a catalog that stays queued:

     ```sql
     SELECT c.tenant_key, c.catalog_key, q.last_error
     FROM catalog_upgrade_queue q JOIN catalogs c USING (tenant_key, catalog_id);
     ```

     `last_error` says why. A catalog whose content references another catalog that has no
     release yet waits for it (released in a later round of the same run); one that references a
     catalog the tenant does not have needs that catalog installed; anything else is a defect to
     report with the message.

4. **Deploy those releases** to each environment, from its page (Environments) or with
   `deployCatalogRelease`. Until an environment has a release deployed, a generation request for it is
   refused, and the tenant home names it. A release that renders with releases of other catalogs
   (the system catalog, a shared catalog) uses them implicitly; they need not be deployed.
5. **Resubmit** what failed in the queue:

   ```sql
   SELECT tenant_key, id, correlation_id, error_message
   FROM document_generation_requests
   WHERE status = 'FAILED' AND release_version IS NULL AND draft_id IS NULL;
   ```

Work that was unpublished in 1.x is in each catalog's draft, to be marked ready and released as usual. Each catalog comes across with that one draft, its next version; from 2.0 a catalog can also hold
parallel work next to it (see [drafts](catalog-versioning.md#catalog-versions-and-drafts)).

## Backups and upgrades

**Take a database-level backup before upgrading, and verify it restores.** It is the only rollback
path for a breaking upgrade (see below). Epistola keeps no per-tenant backups of its own: tenant
backups were withdrawn in 2.0.0, and released catalog content is kept by the releases themselves.

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
