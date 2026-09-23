# From resource relocation to immutable publication

> **Status:** Proposed. The execution plan behind
> [ADR 0025](adr/0025-relocation-without-aliases.md), written during the relocation review of
> September 2026. The direction is accepted; each goal is designed and decided when it starts. It
> does not describe implemented behaviour — [Catalog resource relocation](catalog-resource-relocation.md)
> does. Goal 1 is done, in a different shape than first planned: see "Where this stands".

## Where this stands

_Updated 2026-09-22._

Goal 1 is complete, but two of its original instructions were reversed once it was clear that
aliases had never shipped:

- **Aliases were removed, not contained.** No release and no published image carried them, so there
  was no legacy data to protect. The alias table, reservation, write-time canonicalisation, export
  materialisation, UI/REST/MCP redirects and snapshot planting were deleted outright, and the table
  was taken out of the unreleased migrations themselves. The original plan kept them as bounded
  legacy compatibility and left the migrations untouched.
- **Relocation stayed, as a crude move.** Instead of withdrawing move execution, relocation remains
  alpha behind `resource-relocation`. It keeps the planner, the rewrites of drafts, variant
  attributes and theme styles, the pinning and the cycle guard, and leaves nothing at the old
  address. The preview counts the published references that will break.

Everything that assumed legacy aliases — the compatibility inventory, alias handling in Goal 3,
alias retirement in Goal 8 — has nothing left to act on and is trimmed below. What crude moves can
leave behind in the meantime is new: published versions naming an address nothing occupies, which
Goal 3 must report as missing rather than guess.

Next: stage 1C, the first artifact schema slice, together with Goal 2 — unless the dependency model
proposed below is adopted, which reorders the goals.

## Proposed: cross-catalog references pin a released catalog

> **Under discussion (2026-09-22).** Not accepted yet; recorded so it can be reviewed with the rest
> of the plan. If adopted, it changes the order of the goals below and needs its own ADR.

### The rule

- **Within a catalog, references are live.** A catalog's resources are edited together and
  released together, so a template may use the working copy of a theme, font, image, stencil, code
  list or attribute in its own catalog.
- **Across catalogs, references only reach a released catalog.** Catalog A declares B as a
  dependency at an exact release — A depends on `B@1.0.1` — and every reference from A into B
  resolves in that release: `stencil header@B#1.0.1`, never B's working copy. The rule is the same
  for every resource type.
- **One pin per consuming catalog**, not one per reference: A uses one version of B. A published
  artifact still records the exact release each of its inputs came from.
- **The catalog release version is the version of every resource type.** Themes, fonts, images,
  code lists and attributes have no versions of their own; the release they are in is their version.
- A local release of an authored catalog, an installed release of a subscribed catalog and the
  bundled `system` catalog are all "a release of B".

### What it buys

- **Changes to B cannot reach A.** Editing, moving or deleting something in B's working copy only
  shapes B's next release. A keeps `B@1.0.1` until its author bumps the pin, which is an explicit,
  previewable change.
- **Moving between catalogs needs no aliases.** A resource that leaves B is absent from B's next
  release, and everything pinned to an earlier release is untouched. Within one catalog, a move or
  rename rewrites live references by identity.
- **No dependency cycles.** A release can only pin releases that already exist.
- **Exact installation.** "A@2.0.0 needs B@1.0.1" is checkable at publication and installation;
  today a wire dependency is only `(catalogKey, slug)`.
- **It complements self-contained artifacts.** The pin says what A depends on; retained releases say
  where the bytes are. Because `B@1.0.1` is immutable and retained, an artifact can reference its
  content rather than copying it, and rendering still never reads a working copy.

### Consequences for the plan

- **Retained releases become the foundation.** Today `catalog_releases` stores a version, a
  fingerprint and a manifest snapshot, not the release's resources or binaries, so a pin would have
  nothing to point at. Goal 4's retention moves first.
- Proposed order: (1) retained releases; (2) release-pinned cross-catalog dependencies, with picker
  and validation changes and a migration for existing references; (3) sealed template versions,
  whose same-catalog inputs are captured at publication and whose cross-catalog inputs point into
  pinned releases; (4) environments that select artifacts, and identity-based moves within a
  catalog.
- **Wire format.** A dependency becomes `{catalog, origin, version}`, which is `epistola-contract`
  work.

### Where today's model conflicts

1. **Relational links across catalogs.** A template's theme binding
   (`document_templates.theme_resource_id`), an attribute's code-list binding
   (`variant_attribute_definitions.code_list_resource_id`) and the tenant default theme
   (`tenants.default_theme_resource_id`) are foreign keys to live rows in any catalog of the tenant.
   Across catalogs they would become references to a resource as it is in a pinned release.
2. **Unqualified image references** resolve by key across the whole tenant — an implicit
   cross-catalog reference that would have to become explicit.
3. **The tenant default theme** is tenant configuration, not catalog content. Proposed: relying on
   it is not a catalog dependency; a published version freezes the default that applied, and an
   exported catalog falls back to the installer's default, as today.
4. **The `system` catalog** becomes an ordinary pinned dependency. A Suite upgrade that ships a new
   system release does not move existing pins; old system releases must be retained, and adopting
   the new one is an explicit bump.
5. **Existing data.** Cross-catalog references today point at live resources, often in catalogs
   that were never released. The upgrade needs a per-catalog path — release B, pin A to it — and a
   report of what could not be pinned.
6. **Authoring friction.** Using a new shared resource means releasing B first, so a local release
   must be cheap (one action, an automatic patch version) and separate from publishing to Exchange.
   Letting drafts use B's working copy and pinning only at publication was rejected: the preview
   would show content the pinned release does not have.

### Decided in review (2026-09-22)

These settle points that the first draft of the proposal left open.

- **Code lists keep their values in the release.** A URL-sourced list refreshed after a release
  changes only the working copy; consumers see new values after the next release and an explicit
  bump. Inside its own catalog a refresh stays live. The catalog page should say when a source has
  changed since the last release.
- **Stencil versions stay, for internal use.** Within a catalog an insertion pins a stencil version
  as today. Across catalogs the release is the pin: `B@1.0.1` contains exactly one version of each
  stencil — its latest published one at release time — and the stencil's own number remains as
  provenance. A consumer that wants an earlier stencil pins an earlier release.
- **Every render-time input is classified.** Each input is either captured in the artifact
  (template settings such as PDF/A, and everything the version references) or is explicit context
  recorded with the generated document (request data, culture). Today PDF/A comes from the live
  template row, culture from the tenant default, and the catalog fonts resolve in from the
  template's current theme binding; those are the gaps to close. Rendering the same artifact with
  the same context must give the same document.
- **Dependencies are declared with a mode, resolved exactly.** `B@1.0.1` pins a release;
  `B@latest` follows the release currently installed or selected, for subscribed catalogs;
  `B@working` follows a live working copy, for authored catalogs in the same tenant. Drafts follow
  the mode, so local authoring stays direct. Publishing a template version captures the inputs it
  actually used, whatever the mode. Releasing a catalog requires every dependency to resolve to an
  exact release and records it, so `@working` blocks a release until the dependency is released.
- **Retention follows reachability, with a policy for letting go.** Content stays while any
  published artifact or pinned release needs it, even after the resource is deleted from the
  working copy, so the content sweep has to reach through artifacts and releases rather than live
  rows alone. A discard policy decides when an archived version that no environment runs stops
  keeping its inputs alive, and the UI explains why something cannot be purged.
- **A release may only depend on releases its installer can obtain.** Publishing A to Exchange
  requires B's pinned release to be available there, which Exchange can check on submission.
  Dependencies are named by origin (registry, namespace, catalog), never by a tenant-local key.
  Installing A installs or requires its dependencies first. Purely local use needs none of this.
- **A withdrawn release has a severity.**

  | Status       | Existing installs and published work                                                                                                       | New pins, bumps and publications |
  | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------- |
  | `deprecated` | keep working; a newer release is offered                                                                                                   | allowed, with a notice           |
  | `recalled`   | keep rendering, with a prominent warning naming affected templates and environments                                                        | blocked                          |
  | `revoked`    | rendering of artifacts containing it is blocked by default; an incident page lists what is affected and an admin can override deliberately | blocked                          |

  The status travels on the existing upstream check, and the dependency graph produces the affected
  list. A retained copy is kept even when revoked, so the decision stays reversible.

### Deployment: an environment runs a catalog release

_Decided 2026-09-22._ This replaces per-template, per-variant activation.

- **An environment points at one release per catalog** — Production runs `letters@1.9.3` while Test
  runs `letters@2.0.0`. Promotion copies the selection; rollback selects the earlier release. Both
  are pointer changes over retained content.
- **No per-template overrides.** Running different versions of templates from one catalog in one
  environment is not supported; a hotfix is a patch release. The catalog is therefore the blast
  radius of a deployment, which is worth saying in the product documentation.
- **An environment may run a catalog's working copy instead**, which is how unreleased work is
  tested end to end without cutting a prerelease. It resolves each template's latest published
  version by default; rendering drafts is a separate, explicit per-environment choice. Such an
  environment renders live resources rather than a sealed artifact, its documents are marked as
  generated from unreleased content, and its data contracts carry no stability promise. Generation
  history still records the exact revision used, so a document remains explainable.
- **Publishing stays one action.** Every resource editor — template, stencil, theme, font, image,
  code list, attribute — shows which catalog it is in and offers Publish, which cuts a patch
  release of that catalog. Whether an environment then picks it up is the environment's own
  setting; a "follow the latest release" mode for simple installations is worth considering and is
  not yet decided.
- **A deployment preview** names what changes per template, which data contracts change, and which
  templates the release removes that the environment currently serves.
- **Dependencies come with the release.** Deploying `letters@2.0.0` brings the content of the
  releases it pins; a dependency is never deployed separately.
- **REST:** per-template activation (`PublishToEnvironment` on the template API) is replaced by
  releasing a catalog and deploying a release. It is a breaking change, taken deliberately in the
  next major together with the contract's other pending removals, rather than kept alongside a
  legacy mode.

### Versioning: the release is the version

_Decided 2026-09-22._ One version concept for the whole product.

- **Every change produces an immutable revision** of a resource. A catalog release is a manifest of
  revisions, so the version of a resource is the release it is in.
- **Per-template and per-stencil version numbers go.** A template is "invoice, as of
  `letters@2.0.0`"; its page becomes the history of releases in which it changed. Inside its own
  catalog a stencil is named the same way; from another catalog it is named by the pinned release.
- **Ready versus not-ready stays**, for templates and stencils. Editing produces a draft; Publish
  marks that revision ready; a release captures each resource's latest published revision. Without
  it a release would capture a half-finished edit. In the simple case Publish also cuts a patch
  release, showing everything else that release includes; a team that releases deliberately can
  publish resources as they finish and release later.
- **Themes, fonts, images, code lists and attributes stay live** in their catalog, with no draft
  state, as today. The release preview — everything changed since the last release — is the safety
  net. Giving them a draft state too is a separate decision.
- **Stencil insertions** embed a copy and record the revision they came from, so "this stencil
  changed since you inserted it" still works and upgrading is explicit. Inside a catalog that is
  the current published revision; across catalogs it is the revision in the pinned release.
- **Data contracts fold in.** A template's contract is part of its revision, breaking-change
  detection runs when the catalog is released, and the deployment preview says which templates'
  callers a release would break.
- **Consequences.** Everything that names a template version moves to naming a release: generation
  history (release plus revision), generate-by-version over REST (becomes generate-by-release),
  quality findings, load-test runs, and the cap of 200 versions per variant, which a revision
  retention policy replaces.
- **Terminology.** Where the goals below say "published template version", read "the published
  revision a release contains".

### Spun off as their own design topics

- **Style presets.** Presets mix a vocabulary (`heading-1`, `callout`) with its values, and both
  live in a theme today, so a stencil from another catalog names roles the host theme may not have.
  The direction to explore: presets become a catalog resource — a vocabulary with default values,
  referenced and pinned like any other cross-catalog dependency — and a theme only overrides those
  values, with the vocabulary's default as the fallback. The `system` catalog would ship a standard
  vocabulary so shared stencils work in any theme.
- **Backup and restore.** Once nearly everything is an immutable, content-addressed object
  (resource revisions, release manifests, artifacts, binaries) with a small mutable remainder
  (working copies, pins, deployments, settings), a backup is "copy new objects, then the refs":
  append-only, incremental and deduplicated by construction. That may replace both today's
  table-dump tenant backup and the catalog-export snapshot, and should be designed as a whole
  rather than retrofitted.

### Enforcement

- The editor's theme, font, image, stencil and code-list pickers offer the catalog's own resources
  plus the released resources of its declared dependencies.
- Adding a dependency is an explicit choice of catalog and release. Bumping one previews what
  changes for the consuming catalog: resources removed, renamed or changed.
- Saving, publishing and releasing reject a cross-catalog reference to a catalog that is not a
  pinned dependency. `ResourceReferenceSites` already enumerates every reference site, so the check
  has a natural home.

## 1. Decision and intended outcome

Stop extending the current relocation design. Separate mutable authoring resources from immutable
published content, and separate publication from deployment and distribution.

The desired product behaviour is:

- An author can reorganise a working copy without changing previously published documents.
- A published template version retains the exact inputs needed to render it.
- A catalog release retains an immutable collection of resources and their required content.
- Multiple published releases can coexist, with one selected release per subscription for current
  authoring. Historical releases do not become additional editable working copies.
- Installing or selecting a catalog release does not silently change what an environment runs.
- An environment runs a catalog release, or a catalog's working copy where that is chosen
  deliberately. Promoting or rolling back selects retained content; it does not resolve
  dependencies again.
- Subscribers keep existing work usable when a publisher moves or removes resources from a later
  release, without requiring the publisher to know who uses them.
- Local internal references eventually use identity or immutable content, not historical addresses.

Prefer self-contained published artifacts over a runtime dependency solver. “Self-contained” means
all required content is retained and available without consulting current resource addresses or a
remote installation. It does not require duplicate physical storage of identical font/image bytes.

Resource relocation becomes an authoring operation. Cross-installation continuity of future
updates is a separate, optional migration capability. It must not be a prerequisite for rendering
existing published work.

**Release decision ([ADR 0025](adr/0025-relocation-without-aliases.md)):** aliases never ship, and
relocation ships as a crude alpha move behind its toggle, with the preview and documentation stating
what a move breaks. The relational identity migrations are kept. This plan does not authorise a
deployment, push, or release.

## 2. The concepts and their boundaries

| Concept                       | Mutable?                                | Purpose                                                                                                                                                  |
| ----------------------------- | --------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Resource identity             | Identity never changes                  | Identifies an authored template, stencil, theme, font, image, code list or attribute independently of its current address.                               |
| Resource address              | Subject to external compatibility rules | Human/API name; not an internal rendering dependency.                                                                                                    |
| Working copy                  | Yes                                     | Current authoring content and catalog membership. One per authored catalog.                                                                              |
| Template draft                | Yes                                     | Editable document content and explicitly selected dependencies.                                                                                          |
| Published template revision   | No                                      | Exact template model, resolved rendering inputs and contract reference, retained for use.                                                                |
| Published stencil revision    | No                                      | Reusable content and retained dependencies; insertion remains an explicit copy/adoption operation.                                                       |
| Catalog release               | No                                      | Immutable distribution artifact with a version, content digest, provenance and complete content.                                                         |
| Selected subscription release | Yes, by explicit action                 | Chooses which installed release supplies current authoring resources.                                                                                    |
| Environment deployment        | Yes, by explicit deployment             | Points one environment at one catalog release, or at that catalog's working copy, with the configuration used to select a variant and validate requests. |
| Generated document            | No                                      | Output from particular inputs; its existing retention policy remains separate.                                                                           |

### Template publication, catalog publication and environments

Template publication is the first durability boundary. It must work without any catalog release.
Catalog publication packages exact published template versions; it must not re-freeze them using
newer themes, fonts or contracts.

For example, `letters@2.0.0` contains invoice version 8. Test can run `letters@2.0.0` while
Production runs `letters@1.9.3`. Promoting selects the release tested in Test. Rolling Production
back selects the earlier release; it does not roll back the catalog working copy. A later release
may still contain invoice v8 while changing other resources.

An environment runs one release per catalog, not one version per template — see "Deployment"
below. Variant selection, default-variant configuration and exact contract references travel with
the deployed release, so an external request cannot silently select a different variant after an
unrelated authoring edit.

### What a resource move means

Moving an authored resource changes its working-copy catalog membership and optionally its authoring
name. Local editable references continue to identify that resource. Published artifacts retain
their original content. Old catalog releases retain their original membership.

If a font moves from `letters` to `shared`, `letters@1` still contains its original font;
`letters@2` may omit it; `shared@1` may expose it. A document published using the old font keeps its
retained font content. Following updates from `shared` is a distinct choice.

For stencils, distinguish the embedded copy from its source provenance. Losing the authoring source
need not prevent rendering the embedded copy, but its fonts, images, parameter metadata and other
actual runtime inputs must also be retained. A changed source reference alone is not evidence that
the rendered stencil changed.

### External addresses

Default policy for the first implementation: established external generation addresses remain
stable. Display names and authoring organisation may change independently. Do not silently replace
a resource at an address external callers already use.

There are no redirects: they were withdrawn with aliases before any release, so the only addresses
external callers can have established are the ones resources occupy now. A crude move breaks one
openly, with "not found". Attribute names used in external variant-selection requests also require
compatibility; they are not merely internal authoring keys.

## 3. Invariants that every implementation step must preserve

1. Publication fixes meaning, not just JSON bytes. A published reference must not start naming a
   different resource because an address was reused.
2. A retained published artifact remains usable after its source is moved, deleted, replaced,
   unsubscribed or unavailable remotely. Its required bytes must remain available locally.
3. A hash detects substitution; retaining the bytes makes rendering possible. Keep both.
4. The same artifact is previewed, tested, promoted and generated. Both the app and `pdfrender`
   consume the same dependency resolver.
5. Publication uses a consistent snapshot of its inputs. Concurrent saves or font replacement
   cannot produce a mixture of revisions.
6. Imported and published artifacts never acquire dependencies by matching a bare catalog key in
   the receiving tenant. Provenance and artifact-local identifiers determine their content.
7. Publication, installation, selection and deployment are separate acts with separate permissions.
   A publisher cannot implicitly deploy into a subscriber's environment.
8. Rollback selects an older retained artifact. It does not delete newer artifacts that other work
   still uses, downgrade the database, or rewrite history.
9. Historical source addresses and version labels remain provenance. Local UUIDs are not portable
   identity, and equal content hashes do not prove two resources are the same logical resource.
10. Tenant isolation applies to artifacts, references, origin bindings, caches and blob access.
11. An archived version is retired from normal authoring/deployment according to policy, not an
    instruction to discard dependencies still required by retained history.
12. Missing legacy content is reported honestly. Never substitute today's font or theme and claim
    that an old published version has been faithfully migrated.

These guarantees concern retained inputs and rendering semantics with compatible rendering software.
Byte-identical PDFs across arbitrary engine upgrades, changing external data or timestamps are a
separate reproducibility contract. Inventory runtime external inputs and either capture them,
require them explicitly per request, or label the resulting guarantee accurately.

## 4. Goal 1 — clean up the current feature and prepare the database

**Done**, with the two reversals described in "Where this stands": aliases were removed rather than
contained, and relocation stayed as a crude move rather than being withdrawn.

### 4.1 What was removed

- The alias table, and the constraint that existed only as its foreign-key target, taken out of the
  unreleased migrations `V20260905090000` and `V20260920160936` under SHA-pinned
  `checkMigrationVersions` exemptions.
- Every alias consumer: render-time fallbacks for themes, fonts, font fingerprints and images;
  queued-generation redirection; UI redirects and the REST/MCP resolution step; address reservation
  and alias release; write-time canonicalisation; export materialisation; alias planting in
  snapshot restore; alias edges in the resource graph; font usage by former address.
- The reserved-address problem type (`RESOURCE_ADDRESS_RESERVED`), which never shipped.

### 4.2 What the crude move keeps

Preview with a plan fingerprint, a tenant lock and all-or-nothing execution; typed rewrites of
drafts, variant attribute keys and theme styles; pinning of relative references inside the moving
resource's own versions and of fonts in frozen theme snapshots; the catalog-dependency cycle guard;
the quality-finding repoint listener; audit. Tests pin what a move breaks: published fonts fail the
integrity check, queued generation fails, old addresses are "not found", and export, release and
snapshot refuse a catalog whose latest published versions name a moved resource until they are
republished.

### 4.3 Keep the structural work that remains useful

Keep:

- Stable `resource_id` values for all seven resource types and typed relational foreign keys.
- Template hierarchy, environments, quality and load-test references already keyed by identity.
- Generation history's recorded address plus identity, including its nullable legacy identity.
- The shared identity registry and current address-sync ownership described by ADR 0020.
- Tenant-qualified keys and constraints, typed key validation, content hashes and blob storage.
- The existing publication outbox and exact-byte retry behaviour.
- Shared reference extraction and diagnostics; deletion/import consumers need consistent coverage.

Do not bundle ADR 0024's identity-allocation refactor into the artifact work. Application versus
trigger allocation is independent of immutable artifacts. Never mint replacement identities as a
shortcut to schema cleanup.

### 4.4 Database discipline for the next schema slices

Every schema change from here on is an ordinary forward migration. Editing merged migrations in
Goal 1 was possible only because no installation had run them, and that exception is spent once the
next release ships.

1. Define the smallest additive schema required by Goal 2. Finalise ownership, keys, retention and
   backup classification before adding its migration; do not create speculative empty tables for
   every later feature.
2. Add new timestamped migrations for actual schema changes. Use nullable additions and staged
   constraints where a backfill is required. Validate counts and tenant/type relationships before
   making fields mandatory.
3. Prepare resumable, idempotent application backfills for expensive publication reconstruction.
   Avoid an unbounded Flyway transaction over every historical template and binary.
4. Make new retained references visible to blob retention before any artifact relies on them.
5. Keep unused compatibility columns/tables until a later verified retirement step can remove them
   without deleting unique user information. Removing code does not require immediately dropping
   every old table.

Use the migration rules in [`.agents/rules/migrations.md`](../.agents/rules/migrations.md),
including global timestamp ordering, module ownership, PostgreSQL support, and backup compatibility
declarations.

### 4.5 First additive schema slice

Proposed logical records below are responsibilities, not a requirement to introduce every named
table immediately. Reuse existing version/release tables where their ownership and lifecycle fit.
The first migration should implement only the publication artifact and retention seam consumed by
Goal 2.

| Record                                       | Required information and constraint                                                                                                                                                     |
| -------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Immutable artifact                           | Tenant, internal artifact identity, kind, schema/renderer compatibility version, canonical payload/manifest and digest. Sealed content cannot be updated in place.                      |
| Artifact binary references                   | Artifact, local content-store scope and full content hash, media type and necessary metadata. References protect actual retained bytes.                                                 |
| Exact artifact dependencies, if factored out | Parent artifact to exact child artifact; tenant/type validation. No address lookup, SemVer range or independently maintained authoring graph.                                           |
| Published version binding                    | Existing template/stencil version to its sealed artifact. Legacy rows may temporarily have no binding, with explicit migration status.                                                  |
| Migration progress                           | Resumable position and actionable outcomes: verified, missing content, ambiguous target, changed content, or unsupported shape. Do not put mutable migration state in a sealed payload. |

Subsequent schema slices add durable catalog release archives, imported origin/release records,
selected-release pointers, deployment configuration and queued-request artifact bindings when
their implementation goal begins.

Database requirements:

- No artifact or retained blob is cascade-deleted merely because its authoring catalog or current
  resource row is removed. Existing catalog/resource ownership cascades are not artifact lifetime.
- Artifact provenance may record a historical source identity/address without requiring that the
  live source row exist forever. Choose a retained identity record or non-owning provenance field
  explicitly; do not create a foreign key whose cascade defeats retention.
- Reference and ownership keys include the tenant. Content hashes are not access tokens, and
  deduplication must preserve the existing sensitive/public storage boundaries.
- Digest identity includes the relevant canonical content and schema semantics; a font-family
  revision includes its face mapping, not just one file's hash.
- Artifact rows and their retention references are committed atomically. Publishing must never
  expose a committed artifact whose required bytes can be swept.
- Update `ContentReaper`'s marking rules: live `assets` rows alone are insufficient once published
  artifacts retain binaries directly. Consider concurrent publish, import and restore; a grace
  period is not the only correctness mechanism.
- Classify new tenant tables and blobs for faithful backup and snapshot export. Do not accidentally
  back up Exchange credentials or replay publication jobs.

### 4.6 Exit criteria for Goal 1

Met, with the reversals above: no alias machinery remains, retained behaviour has named tests,
stable identities and relational integrity survive the upgrade fixtures, and the release can
proceed. The first artifact schema slice (§4.5) is stage 1C: specified here, and added together
with its first consumer rather than as speculative scaffolding.

## 5. Goal 2 — make newly published template versions self-contained

Implement a vertical slice before changing catalog installation.

### Deliverables

- Define one sealed template artifact containing the document model, resolved theme/settings,
  rendering defaults compatibility, exact contract definition/reference and all runtime inputs.
- Capture font-family face selection metadata and the actual face binaries. Capture inline fonts,
  fonts inside embedded stencil content, theme fonts and preset fonts, not only top-level styles.
- Capture image content and any runtime metadata required by stencil parameters or other nodes.
  Embedded stencil provenance remains separate from required rendering inputs.
- Reuse the existing content store with durable retention references. No address-keyed network
  retrieval during published rendering. Artifact URLs and caches use immutable revision/content
  identity with existing tenant authorisation.
- Seal the artifact in the template publish transaction using a consistent input snapshot and
  conflict checks where needed. Fail publication with locations and missing dependencies when the
  artifact cannot be made complete.
- Make real preview and generation, including `apps/pdfrender`, read the sealed artifact. Preserve
  a clearly isolated legacy path for old versions while migration is incomplete.
- Publishing a new version creates a new artifact. Deploying a release never seals its versions
  again. A template can be published without releasing its catalog.

### Exit scenario

Publish a template with a theme, embedded stencil, font and image. Change/remove the live authoring
resources through permitted commands. Its published version still renders from retained inputs;
a new draft can adopt the new content and publish a distinct version. Verify the actual content or
dependency digests, not merely a `%PDF` header. A cold worker and a warm worker agree.

## 6. Goal 3 — migrate existing published and archived data safely

Every version published before Goal 2 reads its fonts and images live by address. Capturing what
it needs is the prerequisite for relying on the artifact path, not an optional later polish step.

### Resolution procedure

1. Inventory a version and its stored frozen theme, integrity pins, explicit references and legacy
   relative reference bases. Resolve under the context that currently gives it meaning.
2. Use stored published snapshots preferentially. Resolve retained binaries and verify existing
   fingerprints. Do not recompute old theme settings from today's mutable theme.
3. For content without historical integrity evidence, distinguish “preserves current resolution”
   from “proves original publication content.” Record the limitation; no invented guarantee.
4. Build and verify a sealed artifact, then attach it as supplemental publication metadata without
   editing the original published payload or its release fingerprint.
5. Record missing/ambiguous/substituted content for recovery. Seek exact bytes from retained
   archives/backups when available. Leave unresolved rows on the legacy render path. A reference
   left dangling by a crude move is missing content, not a move to follow.
6. Repeat safely after interruption. Recheck that relevant source data did not change while a row
   was being captured. Do not overwrite an already verified artifact with newer live content.

Handle drafts separately: populate stable local identities at existing write/preparation seams.
Relative legacy references need their old context resolved once. Do not add `target` by guessing
from a now-reused address. Published artifacts and editable `target` references are complementary;
published rendering must not follow a mutable resource just because its identity is stable.

Include archived versions, subscribed content, frozen snapshots, variant keys, code-list bindings
and generation history. Test source-catalog deletion/recreation, chained moves, move-back, batches
and an address reused after a move. Some ambiguous histories cannot be repaired automatically; keep the evidence
and require a specific recovery decision.

An old release for which only a manifest remains cannot automatically be reconstructed exactly.
Retrieve retained exact archives where possible. Otherwise mark archive availability explicitly;
never rebuild a historical release from today's working copy under the old fingerprint.

### Exit criteria

Every retained version is either verified on the artifact path or explicitly classified as legacy
with the remaining dependencies protected. Runtime switchover is per verified artifact, not a
global flag that assumes migration succeeded everywhere.

## 7. Goal 4 — retain immutable catalog releases and package dependencies

- Extend release storage to retain exact manifest, resource payloads and required binary content
  for every supported published catalog release, including releases never queued for Exchange.
- Reuse sealed template artifacts. Seal other released resource content where needed: stencil
  versions and their dependencies, theme configuration, font families, images, code lists and
  attribute definitions. Avoid introducing mutable “latest” references inside an immutable release.
- Package dependencies under artifact-local identifiers. An included dependency is not silently
  promoted to a top-level catalog member or imported over a subscriber's same-named resource.
- Keep source catalog/resource/version provenance for display and future update discovery. It does
  not control runtime lookup. Include licence/attribution metadata and respect content distribution
  permissions; publication must not accidentally bundle content the publisher cannot redistribute.
- Exporting a named release returns that exact retained release. Preserve the existing GA working-
  copy export semantics through a separate operation or compatible extension; do not silently
  redefine an existing endpoint.
- Publication workers submit the retained release artifact. Releasing outbox retry storage after
  acceptance must not discard the only durable copy of the release.
- Package and validate a complete release offline. Missing required content prevents publication;
  an unpublished source catalog is harmless only when its permitted required content is included.

### Wire-format work

This requires deliberate `epistola-contract` work: artifact-local references, retained binary
mapping, exact template/stencil revisions, origin metadata and integrity validation. Follow the
contract-bump workflow during implementation. Decide whether this is an additive negotiated format
or a new wire version; do not claim an alpha relocation label permits breaking GA catalog/API
contracts. Preserve existing fingerprint algorithms for existing formats.

A wire artifact contains no tenant-local UUIDs. Imported UUIDs are allocated locally. Portable
logical continuity, if later required, uses an explicit publisher-qualified identity/handoff; a
hash identifies content and does not grant ownership or continuity.

### Exit scenario

Release `letters@1`, move and edit its authoring resources, and release a different collection. An
offline fresh tenant can use the retained first release without `shared` or the publisher being
reachable. The exact first archive and its digest do not change.

## 8. Goal 5 — retain installed releases; make upgrade a selection change

Replace in-place mirroring as the only storage model. Retain imported immutable releases and select
one as the current subscription release. Do not create seven complete mutable table copies per
installed release just to imitate the existing mirror.

### Deliverables

- Introduce an origin/release binding independent of a tenant's local catalog display key. For
  Exchange, origin includes registry plus namespace and catalog; for other sources use an explicit
  source identity policy. Namespace changes are not silently inferred as continuity.
- Retain the archive and verified contents atomically before making a release selectable. A failed
  install leaves selection, environment deployments and existing content unchanged; retries reuse
  verified content safely.
- Project current authoring resources from the selected release. During transition, any existing
  subscribed-table projection is rebuildable and must not own published artifact lifetime.
- Keep stable local template identity/version bindings for existing deployments and API handles.
  Distinguish portable source revision from locally allocated version numbers. Re-import is
  idempotent; different content at an existing immutable origin/version is rejected, not overwritten.
- Removing a member in a newer release removes it from that release's current collection. Retained
  versions used by deployments, published templates or drafts remain available with clear status.
- Drafts using a withdrawn member default to retaining their selected revision. Show “source no
  longer present in selected release” and offer explicit adoption/replacement or detachment where
  supported. Do not silently follow a similarly named resource elsewhere.
- Initially continue refusing conflicting top-level local catalog keys. Embedded dependencies
  require no global catalog-name binding. Installing multiple publishers under remapped display
  keys is optional later work, not a prerequisite for correct rendering.
- Handle previously installed mirrors before pruning: capture required published inputs and origin
  bindings, then switch selection semantics. Do not delete old identities carrying deployment or
  history links during migration.

### Upgrade and rollback rules

Selecting v3 directly from v1 needs no replay of moves performed in v2. Selecting v1 after v3 leaves
v3 artifacts available to work that still references them. Selecting a release never changes
Production. Queued jobs already bound to an artifact continue using it.

Stencil revisions keep their exact source version/content association within each artifact.
Existing `OnStencilConflict` rules remain for legacy authored merge imports; they must not renumber
pins inside retained published artifacts or treat a moved stencil as permission to overwrite an
unrelated version at the destination.

### Exit scenarios

- All seven types removed/moved/renamed by a publisher; subscriber published work survives.
- Upgrade target first, source first, skip a release, move back, retry, and interrupt an install.
- An authored or different publisher's `shared` catalog cannot satisfy an artifact's dependencies
  accidentally.
- X publishes content authored using a release from Y; Z installs X's artifact and can render with
  Y offline. X's future update choice remains independent of Z's running work.
- A removal is presented accurately; no “re-import follows the move” promise without a handoff.

## 9. Goal 6 — catalog deployment, generation and rollback

Complete the deployment boundary introduced in Goal 2 before advertising full environment rollback.

- Deploy a catalog release to an environment: one pointer per (environment, catalog), changed
  atomically. No per-template override; a hotfix is a patch release.
- An environment may instead run a catalog's working copy, as "Deployment" above describes.
- The release carries the variant/artifact mapping, default variant, external attribute-selection
  keys/values and relevant contract definitions, so a deployment needs no configuration of its own.
- Promotion reuses the tested release. Do not independently resolve each environment against its
  current catalog selections.
- Resolve an environment-based generation request to exact artifacts when accepting the request.
  Persist that binding for queues, retries and batches. Define batch acceptance semantics explicitly
  so a deployment part-way through a batch cannot accidentally mix versions.
- Keep request-time address, chosen identity/version/artifact, contract and deployment provenance
  in generation history. Listing history must use the intended tenant/catalog/identity scope;
  unrelated templates with the same key must not merge unintentionally.
- Decide archive/reactivation policy explicitly. Archived artifact retention is independent of
  whether the UI allows deploying it; do not silently widen that permission during migration.
- Convert existing per-template activations by synthesising, per catalog and environment, a release
  that captures exactly what is deployed, then deploying it — so the conversion changes no
  behaviour.
- Keep API/MCP reads and generation semantics deliberate: per-template activation over REST is
  replaced rather than preserved (see below), and exposing raw internal artifact IDs is not
  required.

Exit: Production runs `letters@1.9.3`, Test runs `letters@2.0.0`; editing a theme or selecting a
different release of a dependency changes neither. Promote `2.0.0`, enqueue generation, roll back
to `1.9.3`: the accepted request still uses `2.0.0`, and new requests use `1.9.3`. A worker with an
empty cache produces the expected content.

## 10. Goal 7 — replace the crude move with identity-based references

Only replace the crude move once editable references use identity and published versions use
artifacts.

- Populate typed local targets at all template/stencil/theme/variant write seams and on import.
  Resolve them for editor display; old displayed addresses are provenance, not execution targets.
- Adopt identity-based attribute associations internally while preserving external selection names
  through deployment configuration. Keep code-list relational identity bindings.
- A move updates resource membership/address with permissions and collision checks. Published
  payloads, release manifests and artifacts are untouched.
- Handle stale editor saves with identity and normal optimistic concurrency. Do not let an old tab
  rebind a reference to a new occupant of the old address.
- Preserve established external addresses. Begin with moves that need no external address change;
  offer external renaming/transfer only through a separate explicit compatibility workflow.
- Retain a small policy preview if useful: destination, permission, collision and external contract
  effects. Remove tenant-wide rewrite planning and its fingerprint protocol.
- Separate authoring graph cycles from publication completeness. A cycle between source catalogs
  is not automatically a restore failure once published artifacts are self-contained. Real content
  recursion remains subject to domain validation. Remove the move cycle restriction only after
  restore/import no longer relies on it.
- Consider UI, REST and MCP deliberately. Do not add three new move APIs merely to obtain parity
  before the authoring semantics are proven.

Exit: local move/rename/batch/chain/move-back preserves identities and draft targets, and never
changes existing published artifact digests. Address handovers cannot retarget published content.
Deleted or moved authoring sources do not affect published rendering.

## 11. Goal 8 — retire the legacy render path and complete retention/restore

### Legacy render path

There are no aliases to retire ([ADR 0025](adr/0025-relocation-without-aliases.md)). What retires
here is the path that renders a published version by resolving its dependencies live by address:

- Count remaining legacy versions by lifecycle. Zero unverified versions, rather than elapsed time,
  permits removing the legacy path.
- Migrate supported old backups and snapshots at ingestion before they become live, so restoring
  one does not reintroduce a version without retained inputs after the legacy path is gone.
- Remove move-time pins and obsolete rewrite strategies in small slices once nothing depends on
  them.

### Retention and restoration

- Published artifacts, retained catalog releases, deployments, pinned drafts, active queued jobs
  and supported rollback windows are retention roots. Generated PDF retention is a separate policy.
- Reclaim blobs only when no root reaches them. Make reference registration and collection safe
  under concurrent publication/import/restore. Start conservatively; automatic reclamation can
  follow correct retention rather than leading it.
- Define explicit deletion and storage visibility. Keeping every historical release forever is a
  product/storage decision; deleting an artifact still required by a retained version is invalid.
- Faithful tenant backups include artifacts, selection/deployment records, identities and blobs.
  Preserve original version numbers and isolate installation-specific Exchange authority/outbox.
- Distinguish the current catalog-export-based tenant snapshot from a full authoring backup. It
  must not be described as restoring every draft/history/deployment unless its format actually
  carries those records. Upgrade its format or retain a clearly scoped guarantee.
- Restore retained published content without replaying remote publication or depending on current
  remote catalogs. Validate all required bytes before making restored state visible.
- Restoring into another installation requires a defined tenant identity/import policy and the
  necessary backup encryption keys. Do not treat changing the tenant key as an ordinary restore.
- Restoring an older state must restore tenant default-theme semantics by identity/configuration,
  not attempt to find only the address it happened to have immediately before restore.

## 12. Optional later goal — portable resource continuity

Defer automatic “follow this resource to its new catalog” until the product actually needs it.
Existing artifacts must already work without it.

If implemented, a handoff must name authenticated publisher-qualified source/destination identities
and exact releases, preserve a subscriber's established local identity where that is the promised
behaviour, retain old external addresses according to policy, and be idempotent across partial
installs, retries, skipped releases, chained moves and moves back. It must not merge resources just
because their keys or content hashes match.

Subscribers explicitly choose whether to adopt future updates. Read-only subscribed content and
their own authored/published work are not rewritten silently. Cross-namespace moves require
authority and provenance on both sides; the current namespace binder alone is not that protocol.

With immutable publication, this protocol migrates future authoring/update relationships. It no
longer has to repair every historical published document to keep rendering operational.

## 13. Delivery order and bounded change sets

| Stage | Reviewable unit                                                                    | Requires                                      |
| ----- | ---------------------------------------------------------------------------------- | --------------------------------------------- |
| 1A    | Done: aliases removed before release; relocation kept as a crude alpha move        | —                                             |
| 1B    | Done: alias consumers deleted; the decision recorded in ADR 0025                   | 1A                                            |
| 1C    | Specify first artifact schema; add retention-aware storage with its first consumer | 1B, schema/backup review                      |
| 2A    | Seal a newly published template with theme/font/image content                      | 1C                                            |
| 2B    | Real preview and worker generation from the same artifact                          | 2A                                            |
| 3A    | Resumable legacy capture with explicit unresolved outcomes                         | 2B                                            |
| 3B    | Stable editable reference targets across all writers                               | Reference shape/contract decision             |
| 4A    | Durable exact catalog release storage and new package format                       | 2B, wire compatibility design                 |
| 5A    | Retained imports and explicit selected-release pointer                             | 4A                                            |
| 5B    | Subscriber upgrade/rollback without destructive mirror semantics                   | 5A, legacy installed-data capture             |
| 6A    | Deployment configuration and queued/batch request pinning                          | 2B, compatibility design                      |
| 7A    | Small identity-based authoring move                                                | 3B, artifact coverage of affected history     |
| 8A    | Retire the legacy render path and obsolete move-time pins                          | Verified migration and legacy restore support |
| 8B    | Reference-aware reclamation, full backup and restore guarantees                    | Artifact/import/deployment schemas stable     |

Integrate backup and basic retention support with each schema addition; stage 8 completes retirement
and policy, rather than postponing protection of new data. No step should require “ship all the new
architecture at once.” Use conventional commits for meaningful units and never push without approval.

## 14. Verification focused on product guarantees

Implementation needs a small set of decisive end-to-end scenarios, supplemented by focused domain
tests. Passing a large test count is not a substitute for these guarantees.

| Scenario                                                      | Required observation                                                                                              |
| ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Font/image/theme changed after template publication           | Published rendering retains the recorded content; draft can adopt new content.                                    |
| Stencil source moved or removed                               | Embedded published content and its runtime dependencies remain usable; update provenance is explained separately. |
| Catalog move out, rename, move back, release skipped          | Old release artifact unchanged; no consumer history must replay local moves.                                      |
| Subscriber owns same catalog/resource key from another origin | No accidental dependency binding or overwrite.                                                                    |
| Transitive publisher X uses Y, subscriber Z uses X            | Z can use X's complete artifact without Y being online or installed globally.                                     |
| Upgrade followed by environment rollback                      | Exact older tested artifact selected; other deployments and accepted jobs unchanged.                              |
| Published variant configuration later edited                  | Deployed external selection and contract semantics remain fixed until the next deployment.                        |
| Cold render worker and warm app node                          | Same retained dependency content; no reliance on address-cache warmth.                                            |
| Delete current resource/catalog and run content reaper        | Retained artifact bytes survive; only genuinely unreferenced content is reclaimable.                              |
| Restore a backup or snapshot taken before artifacts existed   | Versions converted or kept on the legacy path safely, with no new remote publication.                             |
| Legacy font bytes missing or fingerprint mismatched           | Explicit unresolved migration result; no fabricated successful backfill.                                          |
| Publication races autosave/import/blob replacement            | One consistent artifact or an actionable retry; never a mixed publication.                                        |

Use command-seeded integration fixtures, real published `PreviewDocument` rendering or the production
generation executor, and two tenants for publisher/subscriber isolation. Exercise Exchange using the
fake server where origin/worker behaviour matters. Run one Docker-backed Gradle task at a time and
use `--no-watch-fs`. Extend data-preservation fixtures with moved resources and published
dependencies; cover PostgreSQL 17 and 18 and both old-to-new and current-to-new upgrades.

Scope tests to the implementation slice; use the repository's tests skill and local guides. Run
formatting and the relevant migration/schema/backup guards. For a PR, follow the repository's full
required checks. Demonstrate each new user-facing capability in the demo catalog where it can be
represented; use an explicit transition scenario for capabilities requiring a state change.

## 15. Product decisions to settle at the relevant milestone

The direction above is the working default. These choices should be recorded before implementing
their dependent goal, not used to stall initial cleanup:

1. **External addresses:** confirm stable published generation addresses as the initial policy;
   decide whether stencils and externally supplied attribute keys have the same promise. No
   redirects exist meanwhile; a crude move breaks an address openly.
2. **Draft dependencies:** default to retaining the last selected revision and showing an available
   update or withdrawn source. Decide whether particular draft references intentionally track the
   current working copy, and make that mode visible.
3. **Retention:** define the minimum rollback/history window and who may explicitly discard an
   unreferenced release. Retained versions always keep required content.
4. **Reactivation:** decide whether archive is reversible and which permissions restore deployment
   eligibility; do not equate archive with blob deletion.
5. **Distribution:** confirm that permitted dependency content may be included in releases and how
   provenance/licensing is presented. Missing distribution authority blocks packaging.
6. **Compatibility:** choose the negotiated/new catalog wire format and GA API transition before
   changing import/export or environment selection contracts.
7. **Incomplete history:** define operator recovery for missing legacy inputs and distinguish
   verified historical capture from capture of the only currently available state.
8. **Deployment scope:** _decided 2026-09-22_ — an environment runs a catalog release, or a
   catalog's working copy; there is no per-template deployment. See "Deployment" above.
9. **Cross-catalog dependency model:** whether cross-catalog references pin a released catalog, as
   proposed above, and how its six conflicts are resolved.

## 16. What this plan deliberately avoids

- Reverting the stable identity/foreign-key work or resetting databases.
- Rewriting immutable released bytes to follow current resource addresses.
- A general SemVer dependency solver or automatic upgrades of dependencies.
- A portable handoff protocol as a prerequisite for keeping old documents working.
- Cloning a full mutable catalog database for every historical release.
- Treating an existing content hash or outbox archive as sufficient retention without lifecycle work.
- Removing external compatibility accidentally while simplifying internal references.
- Promising exact reconstruction of historical releases or dependencies whose bytes no longer exist.

## 17. Implementation entry points and documents to update

- Current move machinery: `catalog/relocation/`, `CatalogOrganiseHandler`,
  `modules/editor/.../catalog-organise/`.
- Identity and references: `catalog/identity/TenantResourceIdentities`,
  `catalog/graph/ResourceReferenceSites`, `TemplateDocumentPreparation`, stencil write paths and
  variant attribute writes.
- Publication/rendering: `PublishVersion`, `PublishToEnvironment`, `FontSnapshotVerifier`,
  `FontByteCache`, `DocumentPreviewRenderer`, `DocumentGenerationExecutor`, `apps/pdfrender`.
- Distribution: `CatalogContentBuilder`, `ReleaseCatalogVersion`, `ExportCatalogZip`,
  `ImportCatalogZip`, `InstallFromCatalog`, `UpgradeCatalog`, `PreviewCatalogUpgrade`,
  `CatalogUpgradeAnalyzer`, Exchange namespace/origin and publication workers.
- Durability: `ContentReaper`, content stores, `catalog/snapshot/`, tenant backup primitives,
  table-topology classifications and data-preservation migration tests.
- Permanent design records: [ADR 0025](adr/0025-relocation-without-aliases.md) supersedes
  ADR 0014's alias half, and this plan supersedes the unfinished steps of the
  [identity migration plan](catalog-resource-identity-migration.md). As goals land, update
  `docs/catalog-versioning.md`, `docs/version-axes.md`, `docs/stencils.md`, `docs/storage.md`,
  `docs/tenant-backup.md`, the Exchange installation and publication docs, the reference-graph
  guide and the [relocation guide](catalog-resource-relocation.md). ADR 0020 remains useful; ADR
  0024 is independent work.

The direction is recorded in ADR 0025. Each goal records its own decisions in an ADR and updates
the canonical guides when it starts. Keep this plan updated while it is the agreed work sequence.
