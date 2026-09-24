<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0026: Revisions, releases and the working copy

- **Status:** Accepted — stage 1 implemented
- **Date:** 2026-09-23
- **Deciders:** Epistola team
- **Tags:** catalog, versioning, publication, storage
- **Related:** [ADR 0025](0025-relocation-without-aliases.md) (why published content becomes
  self-contained), [the immutable publication plan](../catalog-immutable-publication.md),
  [#975](https://github.com/epistola-app/epistola-suite/issues/975) (stage 0 of the implementation)

## Context

The plan decided that a catalog release is the only user-visible version, that environments deploy
releases, and that cross-catalog references pin one. None of that says where content lives, what a
resource's state is between releases, or how the same content avoids being stored twice. This is
stage 0 of #975 and everything else rests on it.

Storage today:

- `template_versions` keeps a **full copy** of `template_model` for every version, plus
  `resolved_theme`, `referenced_paths` and a contract pointer, with `status` draft/published/archived
  and a cap of 200 per variant. `stencil_versions` has the same shape for `content` and
  `parameter_schema`.
- Themes, fonts, images, code lists and attributes have **no history**: one live row each, with
  child rows (`font_variants`, `code_list_entries`) and binaries already content-addressed through
  `assets.content_hash` and the content store.
- `catalog_releases` keeps a version, a fingerprint and a manifest snapshot — **no content**, so an
  old release cannot be reproduced.
- While a release is queued for Exchange, `catalog_release_publication_archives` holds the exact ZIP
  until Exchange decides, because nothing else retains it.

So versioned types duplicate their payload on every publish, unversioned types cannot be recovered
at all, and a release is a promise rather than a thing.

## Decision

### 1. Three levels

| Level            | What it is                                                                    | Visible |
| ---------------- | ----------------------------------------------------------------------------- | ------- |
| **Revision**     | one immutable content record for one resource, identified by a digest         | no      |
| **Release**      | a catalog at a semver version: a manifest mapping each resource to a revision | yes     |
| **Working copy** | the mutable current state of a catalog — today's domain tables                | yes     |

A resource is named by the release it is in: "invoice, as of `letters@2.0.0`". Internal references —
a stencil insertion, an artifact input — name a revision digest. Cross-catalog references name a
release, as ADR 0025's plan requires.

### 2. Every resource has a status

Each resource has a **working digest** — the SHA-256 of its canonical payload, by the rule in §4 —
and a nullable `ready_digest`. State is then derived rather than tracked:

| State        | Condition                                           |
| ------------ | --------------------------------------------------- |
| **Released** | `working_digest` equals the last release's revision |
| **Modified** | it does not                                         |
| **Ready**    | `working_digest` equals `ready_digest`              |
| **New**      | the resource is in no release yet                   |

Marking ready stores the current digest, so a later edit makes the resource modified again without
any flag to clear. This applies to every resource type, including themes, fonts, images, code lists
and attributes, which have no draft state today.

**The working digest is derived, not stored, until there are revisions.** A column on every resource
table means every save path recomputing a canonical payload, and a path that forgets leaves a digest
that is silently wrong — the one failure this design cannot absorb, because the release is built
from it. It is computed instead from `CatalogContentBuilder`, already the single source of the bytes
a release and its fingerprint are made of, so the status and the release cannot disagree. The cost
is a catalog build per read, which is what the drift check already paid. From stage 2 a revision is
written at one choke point, and `working_digest` becomes a column that is cheap to keep right.

**A release refuses while anything in the catalog is modified.** Running a release presents the
review screen — everything modified, ready, added or removed, with contract breaking changes per
template — and can mark everything ready in one action. Ready is therefore a review checkpoint, not
a filter: a release always contains the catalog's whole working copy.

That refusal and `ready_digest` arrive together, with the major release that makes the catalog
release the only version. Until then a release captures the working copy whatever its state, so
readiness would be a flag nothing reads; the review screen ships first and reports.

**A template is the unit**: its settings, all its variants and its contract form one revision.
Dirty state is tracked per variant so the review screen can say which variants changed.

**Permissions.** Marking ready needs no permission beyond editing the resource. Releasing is its own
permission, `CATALOG_RELEASE`; `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH` retire with the next major,
and `CATALOG_PUBLISH` keeps its meaning: sending a release to Exchange.

### 3. Storage

```sql
-- Immutable content, deduplicated by digest. A payload is the protocol form of one resource.
CREATE TABLE resource_revisions (
    tenant_key TENANT_KEY  NOT NULL,
    digest     CHAR(64)    NOT NULL,          -- sha256 of the canonical payload
    kind       VARCHAR(20) NOT NULL REFERENCES resource_revision_kinds (kind),
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, digest)
);

-- The binaries a revision needs. Bytes stay in the content store; these are retention roots.
CREATE TABLE revision_binaries (
    tenant_key   TENANT_KEY NOT NULL,
    digest       CHAR(64)   NOT NULL,
    scope        TEXT       NOT NULL,
    content_hash TEXT       NOT NULL,
    PRIMARY KEY (tenant_key, digest, scope, content_hash),
    FOREIGN KEY (tenant_key, digest) REFERENCES resource_revisions (tenant_key, digest) ON DELETE CASCADE,
    FOREIGN KEY (scope, content_hash) REFERENCES asset_content (scope, content_hash)
);

-- What a release contains: the manifest as rows, with enough metadata to list without payloads.
CREATE TABLE release_entries (
    tenant_key      TENANT_KEY   NOT NULL,
    catalog_key     CATALOG_KEY  NOT NULL,
    version         VARCHAR(50)  NOT NULL,
    resource_type   VARCHAR(20)  NOT NULL,
    resource_key    TEXT         NOT NULL,       -- the address inside this release
    resource_id     UUID         NOT NULL,       -- identity, for provenance
    revision_digest CHAR(64)     NOT NULL,
    fingerprint     CHAR(64)     NOT NULL,       -- the wire digest, for comparing the working copy
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    PRIMARY KEY (tenant_key, catalog_key, version, resource_type, resource_key),
    FOREIGN KEY (tenant_key, catalog_key, version)
        REFERENCES catalog_releases (tenant_key, catalog_key, version) ON DELETE CASCADE,
    FOREIGN KEY (tenant_key, revision_digest)
        REFERENCES resource_revisions (tenant_key, digest) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX idx_release_entries_resource ON release_entries (tenant_key, resource_id);
```

Three details the shipped tables settled (`V20260923162028`). The kind is its own lookup rather than
`catalog_resource_types`, which holds the catalog wire's own tokens so that a registry address is the
triple an export uses — a revision kind is storage, and includes `templateModel`, which the wire
never names on its own. `revision_binaries` carries the dedup `scope` as well as the hash, because
`asset_content` is keyed by both and a sensitive asset's bytes live under its tenant: without it a
hash does not resolve to bytes, and there is no key to point a foreign key at. And that foreign key
is the point — it makes the retention root a fact of the schema rather than a rule the content sweep
has to remember. The media type is dropped: `asset_content.content_type` already holds it, and a
second copy is a second thing to keep in step.

Working copies keep their tables. From stage 2, when revisions give the digest one place to be
written, they gain the status columns:

```sql
ALTER TABLE themes ADD COLUMN working_digest CHAR(64), ADD COLUMN ready_digest CHAR(64);
-- likewise document_templates, stencils, fonts, assets, code_lists, variant_attribute_definitions
ALTER TABLE template_variants ADD COLUMN working_digest CHAR(64);   -- per-variant dirty state
```

`catalog_releases` keeps its version, fingerprint, notes and audit columns, and gains parsed
version components so "latest" is an indexed lookup rather than an application-side maximum:

```sql
ALTER TABLE catalog_releases
    ADD COLUMN version_major INT, ADD COLUMN version_minor INT, ADD COLUMN version_patch INT;

CREATE INDEX idx_catalog_releases_order
    ON catalog_releases (tenant_key, catalog_key, version_major DESC, version_minor DESC, version_patch DESC);
```

Shipped as `V20260923150918`. `catalog_releases.resource_fingerprints` (`V20260923154857`) is the
interim form of `release_entries`: the per-resource digests of a release, recorded because they
cannot be recovered afterwards, and enough to derive every status in §2 before revisions exist.
`release_entries` (`V20260923201010`) replaced it as soon as there were revisions to point at, and
the column was dropped with it: it held derived data that had never been part of a released version,
and two records of one fact is how they come to disagree.

An entry carries **both** digests. `revision_digest` says where the content is stored;
`fingerprint` is the contract's canonical digest over the wire form, which is what the working copy
is compared against to say a resource changed. Neither is derivable from the other (§5), and the
status in §2 reads the second. The revision reference is deferred rather than cascading: a revision
must never be deleted while a release still names it, but deleting a tenant removes both, and
Postgres cascades in an order that reaches the revisions first — checking at commit keeps the
refusal without breaking the cascade.

The text `version` stays canonical — it is in the primary key, the wire format and URLs — and the
components are a derived sort key. They are `GENERATED ALWAYS … STORED` rather than written by the
release command: the value is a pure function of `version`, so it cannot drift, and a row arriving
by any other route — a tenant restore, a future importer — is filled without that writer having to
remember. The pattern yields NULL for a label that is not `MAJOR.MINOR.PATCH`, which is deliberate:
`SemVer.parseOrNull` tolerates legacy labels such as `5.5` or `1`, and those keep null components,
sort last and fall back to `released_at`. Pre-release identifiers are out of scope in `SemVer`; adding them later
needs its own ordering column, because `rc.10` sorts before `rc.2` as text.

The dependency and deployment tables that the plan introduces —`catalog_dependencies` and
`environment_catalog_deployments` — are described there, and reference releases by
`(catalog_key, version)`.

### 3a. A revision is a small tree

A bundled demo template is 20 to 70 KB of JSON. Putting every variant of a template in one payload
would rewrite all of them whenever one variant changes, defeat deduplication, and load a megabyte to
render one variant — while today a single `template_versions` row is read.

So a revision may reference child revisions by digest:

| Resource                         | The revision holds                                                  | Children                                            |
| -------------------------------- | ------------------------------------------------------------------- | --------------------------------------------------- |
| Template                         | settings, contract digest, variant list with selection attributes   | one model revision per variant, a contract revision |
| Code list                        | header and source configuration, without credentials                | its entries                                         |
| Font                             | family header and face list                                         | face binaries, already content-addressed            |
| Theme, stencil, image, attribute | the whole resource; these are small (the system theme is 600 bytes) | —                                                   |

The child kind is `templateModel`, after the field it is lifted out of, so the reference in the
payload and the row it names agree. It implies no ownership: a model belongs to a template
_version_, which belongs to a variant, which belongs to a template, and a content-addressed row has
no owner at all — two variants with the same model share one.

**Only the variant models are split so far.** A template revision carries its data contract
(`dataModel`, `dataExamples`) and a code list carries its entries inline, rather than as children of
their own. Deferring costs little and can be undone later without a migration: a child is a
`{"revisionDigest": …}` object substituted wherever it appears, so revisions written with content
inline keep assembling unchanged once a new child kind is introduced. What it costs meanwhile is the
thing this section is about — a template whose settings change rewrites its contract examples with
it.

The parent digest covers its children, so a template's digest still identifies the template as a
whole, which is what a release entry needs. Publishing and ready stay at template level: storage
granularity and publishing granularity are different things. Per-variant digests are also exactly
what the review screen needs to say which variants changed.

The wire form is unchanged: an export still serialises a whole template resource with its variants
inline, assembled from the parts, so the archive and its fingerprint stay as the contract defines
them.

### 3b. How content is loaded

Three paths, and the model forces the caller to say which one it means:

| Question                          | Source                                   |
| --------------------------------- | ---------------------------------------- |
| what am I editing?                | the working copy, in the domain tables   |
| what is in release X?             | `release_entries` → `resource_revisions` |
| what does this environment serve? | the deployment pointer, then release X   |

Loading one variant to render it is two primary-key lookups: the template header revision, whose
selection attributes decide the variant without touching a model, and then that variant's model —
or, from stage 4, its sealed artifact, which already contains the resolved theme, font faces and
images. Revisions are immutable, so both cache by digest with no invalidation, including on a cold
render worker.

Diffing two releases — for the release review screen and for a dependency bump preview — is a
self-join on `release_entries` comparing digests per `(resource_type, resource_key)`, with no
payload reads.

### 3c. Why the working copy is not stored as revisions

Keeping the working copy in the domain tables is deliberate:

- **Database-enforced integrity applies to live content**: an attribute's code-list binding is
  `ON DELETE RESTRICT`, a face points at its asset, a template's theme binding is `SET NULL`, and
  catalogs cascade. That is what the identity re-key bought, and it describes the working copy;
  frozen content must not be dragged along by it.
- **A release payload is a different shape**: portable, addressed, without tenant-local identities,
  and it is what the fingerprint is computed over. Export and import already are that
  transformation.
- **Some columns must never ship**: a code list's credentials, refresh errors, `sensitive`,
  `size_bytes`, audit columns.
- **Listing, filtering, sorting, paginating and joining** are ordinary SQL against the domain
  tables; the frozen side needs only "list a release" and "open one resource", which
  `release_entries` serves from its own columns.
- **Editing** updates a column under constraints, with per-row concurrency; a revision is written
  once and never changed.

The cost is one extra copy of a resource's content while it is released and unchanged: normalised
in the domain tables, canonical in one revision. It is per distinct content, not per release.

### 4. The digest rule

A revision's digest is a SHA-256 over the canonical serialisation of its payload, scoped by tenant,
so identical content within a tenant is stored once and never shared across tenants. Canonical
serialisation is deterministic and **is a compatibility surface**: rebuilding a release archive from
stored revisions must reproduce the same bytes, and therefore the same fingerprint, as the release
that was cut. Tests pin this from the first stage.

### 5. Fingerprint and digest are different things

The release **fingerprint** stays what it is today: computed by the portable canonicalizer in
`epistola-catalog` over the built archive, compared by Exchange and by subscribers. Revision
**digests** are internal identity, used for deduplication, dirty detection and artifact inputs, and
never appear on the wire. Deriving the fingerprint from digests was rejected: the algorithm belongs
to the contract, and other implementations have no revisions.

### 6. A subscribed catalog is releases that cannot be modified

A subscribed catalog has no working copy and no status: it is the releases it has installed, one of
which is selected. Upgrading selects another. The bundled `system` catalog has the same shape.

Read paths therefore resolve a resource in a catalog through one seam: the working copy for an
authored catalog, the selected release for a subscribed one. Until every read path goes through it,
the existing mirror of subscribed content into the domain tables stays as a rebuildable cache.

## Considered options

- **A. Keep a full payload per version (today).** Rejected: every publish copies the whole payload,
  and the types without versions still have no history.
- **B. Validity ranges — each resource row records the catalog versions it is valid `[from..to]`.**
  Rejected: reverting to earlier content cannot be expressed without repeating rows, "what changed
  between two releases" becomes range arithmetic, and payloads are still copied per change.
- **C. Content-addressed revisions plus release manifests.** Chosen: identical content is stored
  once, a release costs entries only, and dirty detection is a digest comparison.
- **D. A full object store (blobs, trees, commits).** Rejected for now as more machinery than the
  problem needs; C can grow into it if branching is ever wanted.

## Consequences

- Republishing unchanged content writes no payload. A release that changes one template stores one
  revision and reuses the rest.
- Storage still grows with genuine change, so revision retention replaces the 200-versions-per-variant
  cap, and the content sweep must treat revisions and release entries as roots.
- Every query that reads a resource by catalog moves behind the resolver in §6 — a broad but
  mechanical refactor.
- Because a release refuses while anything is modified, unfinished work in a catalog blocks its
  release. That is the intended trade for a coherent release, and it argues for scoping catalogs to
  a team or domain. An explicit "hold this resource out of the release" escape hatch is left open
  rather than designed here.
- `template_versions.template_model` and `stencil_versions.content` become redundant once revisions
  are backfilled; they are dropped only after verification, in a forward migration.
- The Exchange archive can shrink to in-flight submissions once rebuilt archives are proven
  byte-identical.

## What retires

Once revisions are backfilled and verified, the version tables have nothing left to hold:

| Today                                                            | Becomes                                                               |
| ---------------------------------------------------------------- | --------------------------------------------------------------------- |
| `template_versions.template_model` (published rows)              | variant model revisions                                               |
| `template_versions.template_model` (the draft row)               | the working copy: a model column on `template_variants`               |
| `template_versions.resolved_theme`, `rendering_defaults_version` | the sealed artifact revision                                          |
| `template_versions.contract_version`                             | a contract revision digest in the template header                     |
| `template_versions.referenced_paths`                             | derived; on the working copy for validation, in the payload otherwise |
| `template_versions.status`, the 1–200 cap                        | working and ready digests, release membership, revision retention     |
| `stencil_versions`, `contract_versions`                          | revisions, with their drafts on the working copy                      |
| `environment_activations`                                        | `environment_catalog_deployments`                                     |

Archiving moves with them: today a _version_ is archived so it cannot be activated; it becomes a
property of a _release_ that may no longer be deployed, which is also where a withdrawn release's
severity lives.

## Transition

1. Record what a release contained and derive each resource's status from it, so the review before a
   release names what it will change — `catalog_releases.resource_fingerprints` and
   `GetCatalogResourceChanges` ([#988](https://github.com/epistola-app/epistola-suite/issues/988)).
2. Add the revision tables; write a revision when a template or stencil is published, which is where
   `working_digest` becomes a stored column; backfill revisions from existing published versions,
   resumably.
3. Releases store entries and binary references, so a release retains its content.
4. Read paths resolve through the seam; the subscribed mirror becomes a cache.
5. Drop the redundant payload columns and the version cap once the new path is verified.

## Open

- The "hold" escape hatch for work in progress that must not block a release.
- When payloads move to the content store, decided on measured sizes.
- When the Exchange archive table shrinks to in-flight only.

## References

- [From resource relocation to immutable publication](../catalog-immutable-publication.md)
- [ADR 0025: Relocation without aliases](0025-relocation-without-aliases.md)
- [ADR 0007: Catalog wire-format migrations](0007-catalog-wire-format-migrations.md)
- [ADR 0020: Where a catalog resource's address lives](0020-where-a-catalog-resource-address-lives.md)
