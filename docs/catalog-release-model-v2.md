<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Catalog release model v2: the migration plan

> **Status:** Proposed, 1 October 2026. The agreed direction: the catalog release becomes the only
> version, and the old world — numbered template and stencil versions, per-template environment
> activations — is **removed in the next major, with no bridges**. This page is the work sequence
> for that major and the upgrade path into it. It supersedes the goal sequence in
> [From resource relocation to immutable publication](catalog-immutable-publication.md) and the
> v1/v2 split in [#975](https://github.com/epistola-app/epistola-suite/issues/975). The storage
> model it rests on is [ADR 0026](adr/0026-revisions-releases-and-the-working-copy.md); the
> serving model is [#1036](https://github.com/epistola-app/epistola-suite/issues/1036); the stencil
> model is [#1047](https://github.com/epistola-app/epistola-suite/issues/1047).

## 1. The decision in one paragraph

A catalog is its releases; a working copy is the mutable thing that produces them. A release is a
manifest of content-addressed revisions and is the only version a person sees. An environment
deploys one release per catalog. Several releases of one catalog resolve at once. Below the release
there is no version number: a template or stencil in the working copy is a draft that can be marked
ready, and a revision is internal storage identity, never shown. Two worlds cannot coexist in the
database, so the new world is built beside the old as **derived data only**, switched on in the
major, and the old tables go dead at that moment and are dropped once the switch is verified.

What the major removes, by name: `template_versions` as the place a template's content and status
live, `stencil_versions`, `contract_versions` as a separate history, `environment_activations`,
every REST and MCP operation that names a version or an activation, the version pages and the
per-template deployment matrix in the UI, and the permissions `TEMPLATE_PUBLISH` and
`STENCIL_PUBLISH`.

## 2. Where this stands today

- **Built, on `epic/immutable-publication` (PR #1003, CI green, not merged):** the release dialog
  names what a release changes (#988, #989); content-addressed revisions and release entries
  (#990); export and Exchange publication of a release as released; the release page and history;
  forget and delete a release (#1034). On the local branch above it: deletion counts as drift
  (#1037) and the ADR 0026 §6 amendment that a request may name a release and that deployment
  replaces activation.
- **Not built:** the resolver seam, releases for subscribed and system catalogs, the working copy
  without versions, deployments, stencils by revision, the contract major, the migration.
- **Decisive fact:** serving still reads the working copy. `GenerateDocument` resolves a version
  from `template_versions` and never touches a release.

## 3. The target model

### 3.1 Concepts

| Concept         | Mutable | What it is                                                                                               |
| --------------- | ------- | -------------------------------------------------------------------------------------------------------- |
| Working copy    | yes     | The domain tables of an authored catalog. One draft per template variant and per stencil.                |
| Ready mark      | yes     | A stored digest per resource saying "this content is reviewed". Set by _Mark ready_, cleared by an edit. |
| Revision        | no      | Immutable content of one resource, or one part of one, keyed by digest. Internal.                        |
| Release         | no      | A catalog at a semver version: one revision per resource, plus manifest metadata. The version.           |
| Deployment      | yes     | `(environment, catalog) → release`, or `→ working copy` for testing. Replaces activations.               |
| Pin             | yes     | `(catalog, dependency catalog) → release`. Post-major; see §11.                                          |
| Installed cache | derived | For a subscribed or system catalog, the live tables mirror one installed release and are rebuildable.    |

### 3.2 How a resource is reached

ADR 0026 §6, with the amendment from #1036:

| What reaches it                                                        | Resolves to                                                                    |
| ---------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| A reference inside one catalog (a template's theme, a stencil it uses) | whatever release is already being resolved                                     |
| A pin from another catalog                                             | the release it names                                                           |
| A request (generate, preview, REST, MCP, open the editor)              | the release it names; else the environment's deployed release; else the latest |

A same-catalog reference carries no version because it never needs one. Outside an environment and
without a named release, a request resolves to the latest release, **not the working copy**. That is
the behaviour change of the major, and it is why unreleased edits stop reaching production.

### 3.3 Status of a resource

| State    | Condition                                             |
| -------- | ----------------------------------------------------- |
| Released | working digest equals the last release's entry digest |
| Modified | it does not                                           |
| Ready    | working digest equals the stored ready digest         |
| New      | the resource is in no release                         |

A release refuses while any resource is modified. The release screen lists everything modified,
ready, added and removed, and can mark everything ready in one action, so ready is a review
checkpoint, not a filter: a release always contains the whole working copy. The escape hatch for an
urgent fix while unfinished work is in the working copy is #1009 option A — a release assembled per
entry, taking one resource from the working copy and the rest from the previous release — and it
lands in the first minor after the major at the latest.

### 3.4 Stencils

The mechanism agreed in #1047, stated here so the issue can point at a page:

- A stencil node in a template stores the instance-owned parts — the reference
  (`catalogKey`, `stencilId`), the **revision digest** it renders, that revision's **interface
  digest**, the fills keyed by placeholder key, and the parameter bindings. The stencil's content
  inside the node is a cache: the server fills it on every read and does not trust what was stored.
- The editor stays synchronous. It receives an embedded document, as today; only who produces it
  changes. Its client-side draft hydration goes away.
- A stencil revision has an interface digest over placeholder keys and parameter keys, types,
  formats, enums and required flags — not labels, defaults or order. Placeholders and parameters
  get a fixed `key` separate from a renameable label (an additive contract change), so a rename is
  a non-event everywhere.
- When a stencil revision is marked ready: instances whose interface digest is unchanged, or whose
  change is compatible for them (a stencil may accept more, never demand more), move to the new
  revision automatically. Instances the change breaks are **held back** on their old revision,
  marked _needs action_, and the catalog release refuses until they are resolved. The same check
  runs on a pin bump across catalogs, and through the Exchange, because digests travel inside
  releases.
- Within one release there is exactly one revision of each stencil. A template that needs different
  content needs a different stencil.
- Decisions taken here (open in #1047): other templates see only ready stencil revisions, never
  drafts; an orphaned fill blocks the release; "last seen revision" lives on the instance; the
  fixed-key/label split is adopted.

Version numbers appear nowhere in this. That is why `stencil_versions` can go.

### 3.5 Data contracts

A template's contract is part of its revision (`dataModel` and `dataExamples` as one child, #994).
Breaking-change detection runs at release and the deployment preview says which templates' callers a
release would break. A request's payload is validated against the contract of the release it is
generated from — which is why a caller may name a release.

## 4. Principles for the migration

1. **Data is never broken.** Forward, data-preserving migrations only. Code switches in one
   release; schema retires in two steps: dead at the major, dropped later after verification.
2. **No bridges.** New-world data may be derived from old-world rows. Old-world rows never depend
   on new-world data. Nothing decides from both. No column on a version row learns about a revision.
3. **Behaviour-preserving by default.** An environment serves after the upgrade what it served
   before, by auto-releasing what is currently served (§8).
4. **Failure is visible before, and loud after.** A readiness report in the last minor names every
   catalog and environment the migration cannot convert cleanly. After the major, a catalog with no
   release says so on its page and generation fails with a problem detail, never an empty document.
5. **Three surfaces switch together.** UI, REST and MCP change in the same major. No legacy mode.
6. **Alpha and beta features may change without a deprecation path**: relocation, catalog
   publishing and installing, catalog context. GA surfaces follow SemVer, which is what makes this
   a major.
7. **Every change carries evidence.** A failing-before/passing-after test per behaviour, and the
   decisive scenarios in §9 pass on a 1.3.0-shaped database.

## 5. What ships before the major (1.4.0)

Nothing here changes old-world behaviour. All of it is derived data or reporting.

- [ ] **Merge PR #1003**, then the deletion-drift fix (#1037) and the ADR 0026 amendment.
- [ ] **Measure the installed base**: environments whose activations point at a version other than
      the latest published per variant; catalogs never released; catalogs with unreleased changes;
      stencil instances referencing drafts. One query per item, run against every known installation.
      The first number decides §8.3.
- [ ] **Upgrade-readiness report.** A page under the catalogs area, UI first, listing per tenant:
      catalogs never released; catalogs with unreleased changes; environments behind latest;
      catalogs the release command would refuse, including a latest published version that names a
      moved resource; drafts in flight; stencil instances pinned to a draft. Each row says what the
      upgrade will do about it. Operators fix what it lists, and the auto-release becomes a no-op for
      them.
- [ ] **Freeze the old world.** No new features on versions, activations or stencil upgrades. The
      MCP write tools from #1040 (`publish_template_version`, `publish_stencil_version`,
      `upgrade_stencil_in_template`) are documented as changing meaning in the next major.
- [ ] **Apply the issue triage in §10.** Close what dies with the old world, re-scope what carries
      over, so nobody builds on the old model in the meantime.
- [ ] **Record the decisions in §12** — an ADR 0028, _The release is the version: removing versions
      without a bridge_, with ADR 0026's status line updated to "steps 1–3 implemented; v2 planned".
- [ ] **Changelog and upgrade note** announcing that 2.0.0 removes versions and activations, with a
      link to the readiness report.

## 6. The major: work packages

Each package is a reviewable branch off the epic, with its own tests. Order is dependency order;
WP1 and WP2 can start now because they read data that already exists.

### WP1 — The resolver seam

One query family answers "resource X of catalog Y at Z", where Z is a release version, _working_,
or _deployed-for-environment E_. It reads `release_entries → resource_revisions` for a release and
the domain tables for the working copy, and returns domain objects, not wire form — the import
path already knows how to turn a wire resource into domain shape, so the seam reuses it.

- Serves one variant in two primary-key reads: the template header revision, then the model
  revision the selection attributes choose. Caches by digest with no invalidation.
- Every read by catalog moves behind it: generation and the render worker, preview, editor load,
  REST reads, MCP reads, quality, the pickers, usage queries. `UiRestApiSeparationTest` and
  `DomainBoundaryTest` keep the layering honest.
- First consumer that proves it: preview-from-release on the release page, since that needs no
  contract change.
- Resolves #983 (a stencil page ignores the catalog in its URL) and the `GetStencil` key-only lookup,
  because the seam is keyed by catalog.

### WP2 — Releases for subscribed and system catalogs

- Installing from Exchange or from a URL records a release: a `catalog_releases` row with the
  publisher's version and fingerprint, entries with the per-resource fingerprints already stored in
  `installed_resource_fingerprints`, revisions built from the archive. Upgrading installs another
  release; nothing is overwritten. `UpgradeCatalog` becomes "install release N and select it".
- The bundled `system` catalog installs the same way, per tenant. Revisions are tenant-scoped;
  payloads are tiny and font bytes deduplicate in the global scope.
- The live tables of a subscribed catalog become a cache of the selected release, rebuildable from
  it, and nothing reads them except through the seam.
- `catalog_releases` gains per-release provenance for installed releases: origin (registry,
  namespace, catalog) and the upstream version label. Additive.
- Retention: a release is collectable only when nothing references it — no deployment, no pin, not
  the selected one, not the latest. `collectUnreferenced` keeps its reachability walk.
- Resolves the subscribed half of #1001, #1036 step 1, and makes ADR 0026 §6 true.

### WP3 — The working copy without versions

- A template variant's draft moves to a model column on `template_variants`; a stencil's draft to a
  column on `stencils`. `DraftVersionFactory` and the draft row disappear.
- **Mark ready** replaces publish: it writes the resource's revision (the child model revision for a
  variant, the stencil revision for a stencil) and stores `ready_digest`. `working_digest` becomes a
  column maintained at the same choke point. Both are added to the seven resource tables and
  `template_variants`, as ADR 0026 §3 specifies.
- The status in §3.3 is derived per resource and per variant; `GetCatalogResourceChanges` already
  computes most of it against `release_entries`.
- The release command refuses while anything is modified, and the release dialog marks everything
  ready in one action.
- The template header revision is written at release and references the model revisions already
  written at mark-ready; deduplication makes that free.
- The contract becomes part of the template revision (#994's `contract` child), and
  `contract_versions` stops being written. `CheckContractPublishImpact` and the usage overview move
  to release time.
- Resolves #988 (the remainder), #994, #1023's `ArchiveStencilVersion` half (it no longer exists).

### WP4 — Stencils by revision

§3.4, built on WP1 and WP3.

- Node props: `revisionDigest` and `interfaceDigest` replace `version` and `draftVersion`;
  `parameterSchemaSnapshot` is replaced by the resolved revision. An additive change to the template
  model in `epistola-contract`, bundled with the fixed `key` on placeholders and parameters.
- The server resolves instances on read for the editor, preview, generation and REST. The stored
  copy is refreshed on save and ignored on read.
- The interface digest and its canonical form live in the contract next to the fingerprint, so
  another installation computes the same value.
- Held-back instances, the _needs action_ list, the release refusal, the guided screens from #1047's
  UX comment.
- Validation at save and release (parameter schema, placeholders, address-block count — #1028,
  #1044) runs against the resolved document through the same resolver as rendering.
- Resolves #1047, #1024, #1032, #612, #613, #785 (re-scoped: composition pins a revision, not a
  version), #628 (detach keeps the resolved content as plain nodes).

### WP5 — Deployments replace activations

- `environment_catalog_deployments (tenant, environment, catalog, version | working_copy)` with a
  foreign key to `catalog_releases` that **restricts** deleting a deployed release. Forgetting a
  deployed release's content is refused in the command (#1036 question 4).
- Generation by environment resolves the deployment when the request is accepted and records
  `release_version` and `revision_digest` on `documents` and `document_generation_requests`.
  `version_key` stays as provenance for documents generated before the major. Batches bind once, at
  acceptance.
- A request may name a release (contract addition). Payload validation follows the named release.
- Working-copy mode for an environment renders live resources, marks its documents as generated
  from unreleased content, and offers no contract stability.
- Deployment preview: per template, what changes; which contracts break; which templates the release
  removes that the environment still serves.
- The deployment view becomes catalogs × environments, with promote and roll back as pointer changes.
- Resolves #920 (deploy many at once — a release is the unit), #656 and #283 (bulk publish — mark
  ready for all is one action), #79 (promotion workflow), the stage-5 half of #975.

### WP6 — The contract major

`epistola-contract` moves to its next major together with the suite.

- **REST, removed:** `listVersions`, `getVersion`, `updateVersion`, `publishVersion`,
  `archiveVersion`, `getActiveVersion`, `listVariantActivations`, `removeVariantActivation`,
  per-template `PublishToEnvironment`, `createStencilVersion`, `listStencilVersions`,
  `getStencilVersion`, `publishStencilVersion`, `archiveStencilVersion`, `getStencilVersionUsage`,
  `listContractVersions`, and the `versionId` input on generation and preview.
- **REST, added:** mark ready (template, stencil, all), list and get releases (part exists), cut a
  release, assemble-per-entry release (#1009 A, may follow), deploy and undeploy, deployment
  preview, generate and preview by release, a template's release history, stencil usage by release.
  #863 (publication override on the release API) and #433 (catalog version on the API) fold in.
- **MCP:** the version tools from #1040 become `mark_ready`, `release_catalog`, `list_releases`,
  `get_release`, `deploy_release`, `preview_deployment`; `upgrade_stencil_in_template` becomes
  "resolve held-back instances". `docs/mcp.md` and `ComponentTypesIntegrationTest` follow.
- **Catalog wire format:** `StencilResource.version` is an integer today. Decide whether it becomes
  provenance or is removed in the protocol's next version under ADR 0007; the fingerprint algorithm
  for existing formats is preserved either way. A template model's stencil node props change as in
  WP4.
- `checkContractVersionAlignment` and `ApiExceptionMappingsConsistencyTest` are the guards; the
  `contract-bump` skill is the workflow.

### WP7 — The UI

- Template and stencil pages: version lists become release history ("changed in 1.4.0, 1.6.0").
  Publish becomes _Mark ready_. The catalog panel leads with the working copy: _Working copy ·
  changed since v1.1.0_, not _Version: v1.1.0_ (#1036 step 7).
- The deployment matrix is replaced by the catalogs × environments view. The template's
  `/deployments` route and the stencil `/versions` routes go.
- The release dialog gains the ready gate and the held-back instances list; the release page gains
  preview-from-release and deploy.
- Catalog context (#1006, alpha) becomes the default chrome, no longer a toggle.
- `ui-affordances.md`, the markup guards and `UiTestHygieneTest` apply as usual.

### WP8 — The upgrade migration

§8 in full.

### WP9 — Retiring the old world

Code is deleted in the major. Schema goes dead in the major and is dropped later.

- **Delete:** `PublishVersion`, `CreateVersion`, `ArchiveVersion`, `UpdateStencilInTemplate`,
  `StencilContentReplacer`, `PublishToEnvironment`, `RemoveActivation`, `ListActivations`,
  `GetActiveVersion`, `GetDeploymentMatrix`, the version and activation REST controllers, the
  version pages, the editor's draft hydration and stencil upgrade actions, the relocation planner's
  version pins, the stencil draft-reference hydration.
- **Dead at the major, dropped later:** `template_versions`, `stencil_versions`,
  `contract_versions`, `environment_activations`. Before any of them is dropped: the
  `load_test_runs` foreign key into `template_versions` (ON DELETE CASCADE) is dropped as a
  constraint, so a later `DROP TABLE` cannot cascade into history; `documents` already has no such
  key. The drop migration runs only after the verification in §9 has passed on every known
  installation, and keeps `version_key` columns everywhere as provenance.
- **Permissions:** `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH` retire; `CATALOG_RELEASE` (cut a
  release) and a deployment permission arrive; `CATALOG_PUBLISH` keeps meaning "send to Exchange".
  `CONTENT_PUBLISHER` maps to the new ones. Identity-provider role names do not change.
- **Toggles:** `catalog-context` graduates. `resource-relocation` stays alpha; the crude move loses
  its version pins and keeps working on the working copy only.
- **Guards:** `AuthorizationCoverageTest`, `MediatorWiringTest`, `KnownFeaturesTest`, the fingerprint
  tests, the drift ratchet and `checkMigrationVersions` are updated with the code they guard.

### WP10 — Docs, demo, changelog

- Rewrite `docs/catalog-versioning.md` ("one live resource set" is false after WP2),
  `docs/stencils.md` (insertion is a reference, not a copy), `docs/version-axes.md`,
  `docs/catalog-exchange-installation.md` (install is a release), `docs/mcp.md`, the REST docs,
  `docs/upgrades.md` (the 2.0.0 procedure), `docs/auth.md` (permissions). Mark
  `catalog-immutable-publication.md` and this page as records once shipped.
- The demo catalog demonstrates mark ready, release, deploy and a held-back stencil instance, with
  `release.version` bumped and `release.fingerprint` regenerated.
- One changelog fragment per package; the release notes lead with what an operator must do.

## 7. Data migration: table by table

| Table or column                             | At the major                                                                  | Later                               |
| ------------------------------------------- | ----------------------------------------------------------------------------- | ----------------------------------- |
| `template_versions`                         | Draft row copied to the variant's model column; published rows unread         | Dropped after §9 verification       |
| `template_variants`                         | Gains `model`, `working_digest`                                               | —                                   |
| `stencils`, `stencil_versions`              | Draft copied to `stencils.draft_content`; versions unread                     | Versions dropped after verification |
| `contract_versions`                         | Current published contract folded into the template revision; unread          | Dropped after verification          |
| seven resource tables                       | Gain `working_digest`, `ready_digest`                                         | —                                   |
| `environment_activations`                   | Converted to deployments by §8; unread                                        | Dropped after verification          |
| `environment_catalog_deployments`           | New, written by §8                                                            | —                                   |
| `catalog_releases`                          | Gains provenance for installed releases; `content_retained` true for new rows | —                                   |
| `catalogs.installed_*`                      | Reinterpreted as "selected release"; unchanged shape                          | The ERD split in #1036, when forced |
| `catalogs`                                  | Gains `base_version` (#1036 step 5)                                           | —                                   |
| `documents`, `document_generation_requests` | Gain `release_version`, `revision_digest`; `version_key` nullable provenance  | —                                   |
| `load_test_runs`                            | Gains release columns; FK into `template_versions` dropped as a constraint    | —                                   |
| `quality_findings.version_key`              | Provenance; new findings name a revision                                      | —                                   |
| `feature_toggles`                           | Rows for graduated keys left in place; `FeatureToggleService` ignores them    | —                                   |

Every migration is additive or data-copying. The only deletions are of constraints, and the drop of
dead tables is a separate, later migration gated on verification.

## 8. The upgrade: auto-release what is served now

### 8.1 Why auto-release

Today an environment serves the working copy's latest published versions. A release cut from the
working copy at upgrade time is exactly that content, frozen. It is behaviour-preserving for every
environment on latest, bounded by catalog size rather than history, uses the release command and
stores that exist, and gives the never-released and the retained-nothing cases a release in one
move. It also answers #1036's first open question: activations that no release contains get one.

### 8.2 The job

An application-level job that runs at startup after Flyway, blocks the readiness probe until done,
and is idempotent so a restart resumes it. One transaction per tenant. Not a Flyway transaction.

For each tenant:

1. **Authored catalogs.** If the latest release is retained and its fingerprint equals the working
   copy's, use it. Otherwise cut a release from the working copy — patch-bumped, or the first-release
   default when the catalog has never been released — with the note _Cut automatically by the
   upgrade to 2.0.0_ and `released_by` null. Published-only content, as every release today; drafts
   survive as drafts in the new columns.
2. **Subscribed catalogs.** Record the installed version as a release from the mirror, with the
   publisher's fingerprint and the stored per-resource fingerprints. The system catalog likewise.
3. **Environments.** For every `(environment, catalog)` with at least one activation, deploy the
   release from step 1 or 2.
4. **Working copies.** Copy each variant's draft row and each stencil's draft into the new columns;
   set `working_digest` from the current content and `ready_digest` from the auto-release for
   everything it contained.
5. **Stencil instances.** For every stored document, map `stencilId + version` to the revision
   digest of that stencil version's content while `stencil_versions` still exists, and compute the
   interface digest. Instances referencing a draft map to the draft and are reported.
6. **Record the outcome** per catalog and environment in a migration table the readiness page reads
   after the upgrade: converted, release cut, or failed with the reason.

### 8.3 The one behaviour change

An environment serving version 7 while version 8 is the latest published will serve 8. Three
responses, cheapest first; the measurement in §5 decides which:

- No installation has such an environment: the upgrade note says "environments move to the latest
  published content".
- A few do: the readiness report names them and operators deploy latest or accept before upgrading.
- It matters: synthesise, for such an environment only, a release built from the versions it serves.
  This needs the content builder to produce content from a chosen version per variant, which is the
  one piece of old-world code the migration may add.

### 8.4 Failure cases, and what must not happen silently

- **A catalog the release command refuses.** The crude-move case — a latest published version naming
  a resource nothing occupies — renders today from live rows and would not render after the upgrade.
  The readiness report names it beforehand; the job records the failure rather than skipping; the
  catalog page says _Needs a release_; generation returns a problem detail.
- **Unversioned types are captured as they are.** A half-edited theme goes into the auto-release, as
  into any release today. The upgrade note says so.
- **Subscribed fingerprints.** The installed release's fingerprint is the publisher's. Any check that
  rebuilds and compares, as Exchange publication does, takes entry fingerprints from the stored
  digests rather than recomputing over the mirror.
- **Stencil instances on drafts.** Reported, mapped to the draft revision, and listed under _needs
  action_ after the upgrade.

### 8.5 What is deliberately not migrated

Published versions older than the current one are not backfilled into revisions. A revision no
release names would be collected at the next forget or delete, so history would need a retention root
of its own, and nothing in the new world reads it. They stay in the dead table until it is dropped;
`version_key` stays on generation history as provenance.

### 8.6 The operator's procedure

Follows `docs/upgrades.md` for a breaking upgrade:

1. Upgrade to 1.4.x first and open the readiness report. Fix what it lists, or accept each item.
2. Take a database-level backup and verify it restores. Tenant backups no longer exist (#1050).
3. Scale the application to zero. The migration refuses to run while old nodes are live (#906).
4. Deploy 2.0.0. Readiness stays red until the job has finished for every tenant.
5. Check the post-upgrade readiness page: every catalog converted or released, every environment
   deployed, failures listed.
6. Rollback is the database restore from step 2 and the previous image.

## 9. Verification

Decisive scenarios, each a test on a 1.3.0-shaped database through `DataPreservationMigrationIT`
on PostgreSQL 17 and 18, plus the module tiers:

| Scenario                                                                | Required observation                                                                                              |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Environment on latest published, catalog never released                 | After upgrade it serves a release whose content equals what it served before, byte for byte in the resolved model |
| Environment behind latest                                               | Reported before; serves latest after, with the note; or synthesised if §8.3 chose that                            |
| Catalog with drafts and unreleased theme edit                           | Draft survives as draft; theme edit is in the auto-release; status shows modified where it should                 |
| Subscribed catalog at 1.3.0                                             | Has a release 1.3.0, entries match stored fingerprints, serves unchanged; upgrade installs 1.4.0 beside it        |
| Template using a stencil at version 3, another at a draft               | Both instances carry revision digests; the draft one is listed under needs action                                 |
| Two catalogs pin nothing but reference each other's theme               | Both resolve through the environment's deployments; a request naming a release resolves per catalog               |
| Generate by environment, then deploy a newer release mid-batch          | Accepted requests keep the old release; new ones take the new                                                     |
| Delete or forget a deployed release                                     | Refused, naming the environment                                                                                   |
| Font re-uploaded after release                                          | The deployed release renders the retained bytes                                                                   |
| Mark a stencil ready with a removed placeholder filled by two templates | Those two instances are held back, release refuses and names them; the others move                                |
| Catalog the release command refuses                                     | Named in the readiness report; after upgrade its page says needs a release; generation fails loudly               |
| Exchange publication of an auto-released catalog                        | Exchange accepts; the archive fingerprint equals the release's                                                    |
| Tenant with ten catalogs, restart mid-migration                         | Job resumes; outcome table complete; no duplicate releases                                                        |

Guards that must stay green: `MediatorWiringTest`, `AuthorizationCoverageTest`,
`DomainBoundaryTest`, `UiRestApiSeparationTest`, `CatalogExchangeIndependenceTest`, the fingerprint
tests, `KnownFeaturesTest`, `checkContractVersionAlignment`, `checkMigrationVersions`, the drift
ratchet. Before the PR: `./gradlew test uiTest`.

## 10. Issue disposition

| Issue                                                  | Disposition                                                                                |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------ |
| #975                                                   | Replaced by this plan; update the body to point here and close when 2.0.0 ships            |
| #990                                                   | Closes with PR #1003                                                                       |
| #988                                                   | Remainder (ready mark, per-variant detail) → WP3                                           |
| #1036                                                  | Steps 1–7 → WP2, WP1, WP5, WP5, WP3, WP3, WP7; ERD split later                             |
| #1047                                                  | → WP4; this page is the design page it asked for                                           |
| #1009                                                  | Option A in 2.0 or 2.1; F not built                                                        |
| #1037                                                  | Fixed on the branch                                                                        |
| #1001                                                  | Closed as decided; WP2 implements it                                                       |
| #1008                                                  | Carries over; small, independent                                                           |
| #994                                                   | → WP3                                                                                      |
| #1024, #1032                                           | Die with the old world (close, pointing at WP4)                                            |
| #1029                                                  | Dies: fonts are inputs of a release, not pins at publish                                   |
| #612, #613, #628                                       | → WP4 (upgrade UX becomes needs-action; detach keeps resolved nodes)                       |
| #785                                                   | Re-scoped: composition pins a stencil revision inside a release                            |
| #656, #283                                             | Die: mark-ready-all is one action; release is the unit                                     |
| #920                                                   | Die in current form; deploying a release is the replacement (WP5)                          |
| #79                                                    | → WP5                                                                                      |
| #789                                                   | → WP3: contract compatibility checked at release                                           |
| #923                                                   | Carries over, reframed on releases (deployment preview)                                    |
| #863, #433                                             | → WP6                                                                                      |
| #1023                                                  | `ArchiveStencilVersion` half dies; the other two commands stay and need the check          |
| #1021, #1022, #1025, #1026, #1028, #1030, #1031, #1033 | Carry over, unaffected by versions; #1028's limit runs against the resolved document (WP4) |
| #983                                                   | → WP1                                                                                      |
| #755                                                   | Carries over; the seam makes a read-only open of a release's template natural              |
| #922                                                   | Carries over, after the major                                                              |
| #917, #919                                             | Carry over; pins (post-major) are the real dependency                                      |
| #952                                                   | Independent (ADR 0024); not bundled                                                        |
| #911, #908, #912                                       | Relocation and graph; after the major, with stage 9                                        |
| #1050                                                  | Independent; ships before the major                                                        |
| #984, #985, #986                                       | Independent fixes                                                                          |

## 11. After the major

Additive on the new model, in minors:

- **Pins and version ranges.** `catalog_dependencies` with a mode (exact, `@latest`, `@working`)
  and a range. A range constrains the declaration; a release records the exact pin it resolved to —
  the lockfile model. With several releases resolvable at once no solver is needed: a consumer takes
  the highest installed release that satisfies its range. Not for stencil composition (#785).
- **Hotfix assembly** (#1009 A) if it did not make the major.
- **Pre-release ordering** on `catalog_releases` if ranges or "latest" need it; the generated
  columns yield null for `1.0.0-rc.1` today.
- **The ERD split** of `catalogs` into identity, working copy and installation (#1036).
- **Style presets** as a catalog resource; **relocation done properly** (stage 9); **the backup
  replacement** as objects plus refs, if ever needed; **copy a template** (#922); **dependencies
  before install** (#917); **install under a different key** (#919).
- **Dropping the dead tables**, gated on §9.

## 12. Decisions to record in ADR 0028

| #   | Decision                                                      | Proposed answer                                                                                                                      |
| --- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| D1  | Environments behind latest at upgrade                         | Measure; default to latest with a note; synthesise only if the measurement says                                                      |
| D2  | Backfill old published versions into revisions                | No; dead table, provenance only                                                                                                      |
| D3  | Stencil model                                                 | §3.4 as written                                                                                                                      |
| D4  | Release refuses while modified from day one                   | Yes; #1009 A follows immediately                                                                                                     |
| D5  | Draft state for themes, fonts, images, code lists, attributes | No; the release preview is the safety net                                                                                            |
| D6  | Does `catalogs.type` survive                                  | Yes, as a cached discriminator; "has a working copy" is the truth                                                                    |
| D7  | Working copy base on the API                                  | Yes, additive                                                                                                                        |
| D14 | Granularity of a pin into another catalog                     | Catalog pin, built (#1057); per-template overrides under consideration in [ADR 0029](adr/0029-how-a-release-pins-another-catalog.md) |
| D8  | Version ranges                                                | Lockfile model, post-major, with pins                                                                                                |
| D9  | `StencilResource.version` on the wire                         | Decide with the contract bump under ADR 0007                                                                                         |
| D10 | System catalog as per-tenant releases                         | Yes                                                                                                                                  |
| D11 | Where the auto-release version comes from                     | Patch bump, or the first-release default; note names the upgrade                                                                     |
| D12 | What "ready" covers for a template                            | Its own content, fills and bindings; a stencil's ready covers the stencil                                                            |
| D13 | Permissions                                                   | `CATALOG_RELEASE` and a deploy permission replace `TEMPLATE_PUBLISH`/`STENCIL_PUBLISH`                                               |

## 13. Risks

- **Scope in one major.** WP1–WP9 together are the whole of #975's v2 list. The mitigation is the
  order: WP1 and WP2 are derived data and can merge before the switch; WP3–WP7 switch together; the
  migration (WP8) is tested against the readiness report's inventory, not against assumptions.
- **The migration job on a large tenant.** Bounded by catalog size, not history; one release per
  catalog costs what the release command costs today. If a tenant is still slow, the job is already
  resumable and per-tenant.
- **Contract coordination.** `epistola-contract` must ship its major first; `checkContractVersionAlignment`
  refuses a mismatch. The catalog protocol's stencil version field needs ADR 0007's process.
- **Exchange.** The wire format does not change for releases, and publication already submits
  retained content. Install-as-release (WP2) changes the client only. The hub repository needs no
  change for the major; #1050's sibling issue is separate.
- **Serving during the switch.** The old read path is deleted, so the job must finish before the
  application reports ready. The readiness probe is the gate; the procedure says scale to zero.
- **Agents and skills.** `command-query`, `tests`, `ui-page`, `debug-epistola-templates`, the MCP
  docs and `docs/stencils.md` all describe versions. Update them in WP10, or agents will rebuild the
  old world.

## References

- [ADR 0026: Revisions, releases and the working copy](adr/0026-revisions-releases-and-the-working-copy.md)
- [ADR 0025: Relocation without aliases](adr/0025-relocation-without-aliases.md)
- [From resource relocation to immutable publication](catalog-immutable-publication.md) — the plan
  this one supersedes in sequence, still the source of the invariants in its §3
- [Catalog versioning and upgrading](catalog-versioning.md) — current shipped behaviour
- [Upgrades](upgrades.md) — the breaking-upgrade procedure
- #975, #1036, #1047, #1009, #1001, #1050
