<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0026: Revisions, releases and the working copy

- **Status:** Proposed
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

Each resource row carries `working_digest`, maintained when it is saved, and a nullable
`ready_digest`. State is then derived rather than tracked:

| State        | Condition                                           |
| ------------ | --------------------------------------------------- |
| **Released** | `working_digest` equals the last release's revision |
| **Modified** | it does not                                         |
| **Ready**    | `working_digest` equals `ready_digest`              |
| **New**      | the resource is in no release yet                   |

Marking ready stores the current digest, so a later edit makes the resource modified again without
any flag to clear. This applies to every resource type, including themes, fonts, images, code lists
and attributes, which have no draft state today.

**A release refuses while anything in the catalog is modified.** Running a release presents the
review screen — everything modified, ready, added or removed, with contract breaking changes per
template — and can mark everything ready in one action. Ready is therefore a review checkpoint, not
a filter: a release always contains the catalog's whole working copy.

**A template is the unit**: its settings, all its variants and its contract form one revision.
Dirty state is tracked per variant so the review screen can say which variants changed.

**Permissions.** Marking ready needs no permission beyond editing the resource. Releasing is its own
permission, `CATALOG_RELEASE`; `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH` retire with the next major,
and `CATALOG_PUBLISH` keeps its meaning: sending a release to Exchange.

### 3. Storage

```sql
resource_revisions (tenant_key, digest, kind, payload jsonb, created_at)      -- PK (tenant_key, digest)
revision_binaries  (tenant_key, digest, content_hash, media_type)             -- retention roots
release_entries    (tenant_key, catalog_key, version, resource_id, resource_type, resource_key, revision_digest)
```

- `catalog_releases` keeps its version, fingerprint, notes and audit columns.
- Domain tables keep the address, name, settings and the indexes that listing and search need, and
  gain `working_digest` and `ready_digest`.
- Payloads stay in a JSONB column for now. They are small in practice; moving them to the content
  store, as `document_content` did in #738, is a later decision to be taken on measurements.
- Binaries are never copied: revisions reference content hashes that the content store already owns.

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

## Transition

1. Add the tables; compute `working_digest` on save; backfill digests for existing content.
2. Write revisions when a template or stencil is published; backfill revisions from existing
   published versions, resumably.
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
