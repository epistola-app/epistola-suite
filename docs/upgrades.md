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
activated per environment. What each author was working on becomes the working copy, the catalog's
next draft, and what was last published becomes a draft beside it, **Published in 1.x**. The upgrade
cuts no release: you release that draft, and deploy it, to put back what was live. The 1.x template, data contract and stencil
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

Both read what the upgrade keeps — each variant's and stencil's latest published version and its
working copy (its draft, else its latest published version, else its latest archived one), themes, attributes, templates and the catalogs' details — and only authored catalogs' references:
a subscribed catalog receives its source's release. A check that cannot run refuses too. To
go ahead regardless, once you have read why, skip a check by id:
`EPISTOLA_MIGRATION_PREFLIGHT_SKIP=1x-missing-catalogs` (comma-separated; in the Helm chart under
`config.env`). A catalog a skipped check would have reported cannot be released until it is fixed.

**Documents can look different after the upgrade.** Three 1.x choices are not carried over, so check
the documents of each environment before deploying to it:

- **An environment that ran an older version** gets what was last published once the release of
  "Published in 1.x" is deployed: production on a template's version 1 while version 2 went to test serves version 2.
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
- **A published version's rendering defaults** are not kept. 1.x rendered a version with the
  defaults it recorded when it was published, or with 1.x's own (V6) when it recorded none, as a
  version received in a catalog archive does. A release cut in 2.0 renders with 2.0's, V8: block
  spacing comes from the theme (V7), and a container's margins collapse with its first and last
  block's, as on the web (V8). Text does not change, but spacing can: upgrading two municipalities'
  catalogs moved the text on page 2 of 5 of their 28 templates by about a line. A release cannot be
  cut with older defaults, so where a document must keep its layout, adjust the theme's block
  spacing or the template before deploying.

**Generation stops at the upgrade and resumes per environment as you deploy.** Plan the upgrade as
that outage, and tell the teams whose applications request documents.

1. **Drain the generation queue** before scaling down: a request still queued at the upgrade named
   a template version, which 2.0 no longer renders, and fails.
2. **Upgrade** as a breaking upgrade (above), and move REST clients to contract 2.0.
3. **Release what was published.** The upgrade cuts no release (ADR 0035): it runs entirely in the
   migration step, and nothing is done for you after startup.
   - **An authored catalog** keeps its next draft, with any unpublished 1.x work, and gets a draft
     **Published in 1.x** beside it holding what each template and stencil last published (a
     template whose default variant was never published is left out, and so is a stencil with no
     published version, unless a published template uses it: 1.x let a version in use be archived,
     and the draft holds that latest archived version). Where the two would be the same, there is only the next draft. Release "Published in 1.x"
     (or the next draft where there is none) from the catalog's page. Its number is suggested as the
     patch after the catalog's highest 1.x release, or 1.0.0.
   - **Dependencies:** 1.x declared none, so the upgrade declares every catalog a catalog's content
     uses, pinning nothing; releasing pins each to that catalog's latest release (the dialog says
     "Pins System v2.1.0"), and refuses, naming it, a catalog that has no release yet: release that
     one first.
   - **A subscribed catalog** receives its source's release at boot, through the same install as any
     other; a source that cannot be reached is retried at the next boot (the log says why).
   - The 1.x releases themselves are removed: they kept only a number, a fingerprint and notes, so
     nothing could render, export or deploy them, and their Exchange publication records go with them
     (see [`catalog-versioning.md`](catalog-versioning.md#declared-dependencies)).
   - The **tenant home** lists the catalogs from 1.x that have no release yet, and which environments
     make no documents yet.

4. **Deploy those releases** to each environment, from its page (Environments) or with
   `deployCatalogRelease`. Until an environment has a release deployed, a generation request for it is
   refused, and the tenant home names it. A release that renders with releases of other catalogs
   (the system catalog, a shared catalog) uses them implicitly; they need not be deployed.
   **Several installations** (test, acceptance, production) upgrade one by one. Release on the
   first, then import those releases on the others instead of releasing there: an imported
   release moves a catalog's next draft to it when the draft holds the same content, so the
   numbers and content stay the same everywhere. A catalog whose 1.x content differs on that
   installation keeps its draft, and its page offers to merge the release.
5. **Resubmit** what failed in the queue:

   ```sql
   SELECT tenant_key, id, correlation_id, error_message
   FROM document_generation_requests
   WHERE status = 'FAILED' AND release_version IS NULL AND draft_id IS NULL;
   ```

Work that was unpublished in 1.x is in each catalog's draft, to be marked ready and released as usual. Each catalog comes across with that one draft, its next version; from 2.0 a catalog can also hold
parallel work next to it (see [drafts](catalog-versioning.md#catalog-versions-and-drafts)).

**Check who holds which role.** 2.0 changes what roles grant (ADR 0048, contract
`x-authorization`). Nothing is migrated: roles come from your identity provider and API keys, so
review them there before or with the upgrade.

| Change                                                                                               | What to do                                                                                  |
| ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `content-viewer` no longer reads generated documents; `document-generator` does                      | Give the new `document-reader` role to people who read documents without generating them    |
| An API key scoped to Viewer alone can no longer read documents                                       | Add the Document reader (or Generator) scope to a key that fetches documents                |
| Site banner, data sharing and forgetting cluster nodes need `installation-administrator`             | Give the new platform role to whoever runs the installation; `tenant-manager` keeps tenants |
| Exchange connection and quality providers need `INTEGRATIONS_MANAGE`                                 | Nothing: `tenant-administrator` grants it                                                   |
| Load tests need `DIAGNOSTICS_MANAGE` to start, `DIAGNOSTICS_VIEW` to read                            | Nothing for administrators; generators no longer start them                                 |
| Support requests need `SUPPORT_REQUEST_SUBMIT` (viewer) and `SUPPORT_REQUEST_HANDLE` (administrator) | Nothing: the roles grant them                                                               |
| `platform-observer` now reads diagnostics and the audit trail in every tenant                        | Check who holds it                                                                          |

A refused REST request answers `https://epistola.app/errors/forbidden` with `requiredPermission`,
`tenantKey` or `requiredPlatformRole`, permissions named as the contract names them
(`TEMPLATE_EDIT`), instead of `permission-denied`, `access-denied` or `platform-access-denied`
([`authorization.md`](authorization.md#what-a-denied-api-caller-receives)). Update integrations that
match on the old types or on `tenantId` and `requiredRole`.

Every other REST problem names a resource by its key, as 2.0 bodies do: `tenantKey`, `catalogKey`,
`templateKey`, `variantKey`, `environmentKey`, `stencilKey`, `themeKey`, `attributeKey`, `fontKey`,
`codeListKey` and `assetKey`, where 1.x said `tenantId`, `catalogId` and so on. `requestId`,
`documentId`, `releaseId` and `versionId` keep their names. To tell which resource a `not-found` was
about, read its `missing` member, which the contract defines.

## From 2.0.0-alpha.1

After alpha.1, step 9 of the upgrade from 1.x (`V20261101000900`) was corrected. 1.x let you archive
a stencil version that published templates used; a stencil left with no published version was not
put in the "Published in 1.x" draft, which still named it, so the draft could not be released. The
draft now holds such a stencil as its latest archived version, what those templates rendered.
Step 8 (`V20261101000800`) was corrected too: a stencil instance whose copy became plain content lost
the styles set on the instance itself, such as keep together or a margin; it now becomes a container
keeping them. Content alpha.1 converted keeps what it lost: set those styles again where it matters.
The read model (`V20261101001500`) was rewritten as well: it holds every catalog version's content
and chooses none, where alpha.1's views read the version a statement means from session settings
(ADR 0049). Because the upgrade steps changed (step 9, the conversion code steps 5 to 10 share, and
step 15), a later 2.0 version refuses to start on any database alpha.1 migrated, whether it came from
1.x or not: Flyway reports a checksum mismatch, and nothing is changed.

Stop alpha.1 and run [`upgrades/2.0.0-alpha.1-repair.sql`](upgrades/2.0.0-alpha.1-repair.sql) once,
in one transaction, before starting the later version:

```bash
psql "$DATABASE_URL" -1 -v ON_ERROR_STOP=1 -f 2.0.0-alpha.1-repair.sql
```

It records the corrected steps, puts the read model's views in place of alpha.1's, and gives each
"Published in 1.x" draft the stencils it is missing,
as the catalog's next draft holds them: the 1.x versions are gone by then, so a stencil that got a
draft after its version was archived comes with that draft's content, not marked ready. Releasing
refuses until it is ("not ready: stencil '…'"): check it, mark it ready, then release. A
draft that then holds what its next draft holds is removed, as the upgrade does. Running it again
changes nothing, and on a database that did not come from 1.x it only records the corrected steps. A database you rebuild from 1.x or from scratch needs no script.

## Backups and upgrades

**Take a database-level backup before upgrading, and verify it restores.** It is the only rollback
path for a breaking upgrade (see below). Epistola keeps no per-tenant backups of its own: tenant
backups were withdrawn in 2.0.0, and released catalog content is kept by the releases themselves.

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
20261203091500 (not rollback-compatible, recorded by 2.1.0). ...

Action:

To go back to this build, restore a PostgreSQL backup of the database taken before that upgrade,
then start this build again; data written since the backup is lost. To keep that data, deploy a
build that ships migration 20261203091500 or later instead ...
```

So a rollback either works or fails fast; old code never serves from a schema it cannot read. When
every migration in between is compatible, the older build starts and logs a warning that it is
running on a newer schema. Under Helm, `helm rollback` does not run the `pre-upgrade` migration Job,
so a refused rollback shows as the rolled-back pods crash-looping on that message while the pods
still on the newer version keep serving.

**Overriding a refusal.** A refusal is the safe answer, not always the right one: the blocking
migration may touch nothing the older build uses, or the guard itself may be wrong. Once you have
checked that the older build can run on the schema, start it with the guard off —
`EPISTOLA_MIGRATION_ROLLBACKGUARD_ENABLED=false` (`epistola.migration.rollback-guard.enabled`; in the
Helm chart `migration.rollbackGuard.enabled: false`, which reaches the app pods and the migration
step). The build then logs what it would have refused and starts. The migration step keeps
recording declarations, so turn the guard back on once the rollback is done: the next refusal is
then one you have not checked yet. Default `true`.

To know in advance how far back you can go, look at the **rollback floor** — the newest applied
migration that is not rollback-compatible. The migration step logs it on every run
(`Rollback floor: a build must ship … migration=… recordedBy="2.1.0"`), or ask the database:

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

Releases from before the check (those without migration `V20261104203220`) do not perform it, so a
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
