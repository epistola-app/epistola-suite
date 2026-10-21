# ADR 0033: Reading any release, draft or released

- **Status:** Proposed
- **Date:** 2026-10-21
- **Deciders:** Epistola team
- **Tags:** catalog, releases, storage, database, performance

## Context

People want to open any release of a catalog and see its resources the way they see a draft's: the
template with its variants in the editor (read-only), the theme, the code list, the usages. Today
only drafts can be seen this way.

### Where things are stored

- **Drafts** are rows in the content tables (`document_templates`, `template_variants`, `themes`,
  `stencils`, `fonts`, `font_variants`, `assets`, `code_lists`, `code_list_entries`,
  `variant_attribute_definitions`, `catalog_dependencies`, …), keyed by the draft's `version_id`.
  They exist to be worked on: relational columns, foreign keys between resources, ready marks,
  revisions for optimistic locking. Every open draft holds a full copy of its catalog's rows.
- **Releases** are content-addressed (ADR 0026): `catalog_releases` (one row per release, with
  `manifest_snapshot`), `release_entries` (one row per resource of a release, pointing at a revision
  by digest) and `resource_revisions` (a resource's content as JSONB, stored once per tenant per
  distinct content, template variant models lifted into their own revisions), with
  `revision_binaries` → `asset_content` for image and font bytes. An unchanged resource costs a
  release one entry row, not a copy of its content.

### What reads what (survey, October 2026)

- The web UI: about 28 pages show resources; all read the content tables through `versionAt` /
  `inCurrentVersion` and a `CatalogVersionRef`, which can only name a draft. No address opens a
  released release's resources; the release page lists its entries without links.
- The editor (`GetEditorContext`) reads only the content tables and has no read-only mode (the
  theme and data-contract editors have one).
- REST reads released resources, through a second code path in every endpoint (`isDraft`), built
  from the snapshot's protocol model (`CatalogResource`) instead of the domain models.
- MCP reads only drafts.
- Rendering reads releases (`ReleaseRenderSource`, with a bounded Caffeine cache); theme resolution
  and stencil expansion against a release exist only there, privately.
- Usages, the resource reference graph and catalog browsing exist only for drafts.
- `ListReleasedResources` assembles a whole release, its binaries included, on every call.

### Measured, and projected

On the demo database: 11 releases have 81 entries pointing at 71 distinct revisions; an entry row is
about 287 bytes; compressed payloads average 1.2 KB per template, 2.1 KB per template model, 3.2 KB
per code list (bundled templates are 20–70 KB uncompressed, `ResourceRevisionStore`). Each release
also keeps its whole manifest in `manifest_snapshot` (about 11 KB here), including a resource list
that repeats its entries.

A SaaS installation may hold 50,000 catalogs released 50 times each: 2.5 million releases. Assuming
30 resources per catalog, 3 of them changed per release, and 10 KB per changed resource (compressed):

| Storage                                         | Rows                       | Size                                  |
| ----------------------------------------------- | -------------------------- | ------------------------------------- |
| `release_entries`                               | 75 million                 | ≈ 29 GB with indexes                  |
| new `resource_revisions`                        | 7.5 million                | ≈ 75 GB                               |
| `manifest_snapshot`                             | 2.5 million                | ≈ 25 GB, mostly repeating the entries |
| releases copied into the content tables instead | 75 million rows of content | ≈ 750 GB, and no deduplication        |

The content-addressed store is what keeps this in bounds. Rows in the content tables are copies: they
deduplicate nothing, and they are already copied once per open draft.

## Options

### 1. Copy a release into the content tables to view it

Lay a release's snapshot into the content tables as a read-only version row, once, when someone
opens it; every page then works as for a draft.

- **For:** one read path, reused as is; the editor and usages work immediately.
- **Against:** the content tables are for work, and this fills them with frozen copies: an order of
  magnitude more storage at scale and no deduplication, a lifecycle to manage (when to build, when to
  drop), a write path that must refuse those rows, and a copy that can drift from the snapshot it came
  from. **Rejected.**

### 2. Database views over both stores

Views per resource type that present drafts (the content tables) and releases (entries joined to
revisions, the JSONB projected into columns) in one shape, keyed by a version reference.

- **For:** the existing SQL keeps working with a different key; views are read-only, so a release
  cannot be changed by construction; nothing is copied.
- **Against:** every view repeats the mapping from the wire form to the table columns in SQL, a
  second schema to keep in step with both; references between resources are by key in a snapshot and
  by identity in the tables, so the views translate them; tenant-wide reads (search, usages, the
  graph) over a union with JSONB projection cannot use the tables' indexes and degrade with the number
  of releases. Good for simple per-release lists, weak as the general model.

### 3. A read interface with two implementations

One read model per resource type (the domain view models the pages use), behind an interface with a
draft implementation (the content tables, as today) and a release implementation (entries and
revisions, with a bounded cache like the render cache). Pages, MCP, REST and the editor read through
it with a version that may be a draft or a release.

- **For:** storage stays as it is, with its deduplication; REST's second code path becomes the
  release implementation instead of a branch in every endpoint; the editor opens a release read-only
  from the same model; each implementation can be optimised for its store.
- **Against:** a refactor of about 44 queries and 28 pages onto the interface; cross-resource reads
  (usages, the graph, theme resolution, stencil expansion) need a release implementation too — the
  render source has most of them, and a reference index per revision (below) covers usages.

### 4. Content-addressed drafts as well

Drafts become what releases already are: a list of entries pointing at content stored once (git's
index: blobs, and a tree per version). The content tables go.

**Storage**

```
catalog_releases     one row per release, draft or released (ADR 0032 §9's one table)
  release_row_id, release_id, status, version, base, …

release_entries      one row per resource per release, drafts included
  release_row_id, resource_type, resource_key, resource_id,
  digest → resource_revisions,
  name and the summary columns a list needs (variant count, media type, …),
  revision (optimistic locking), updated_at, updated_by

resource_revisions   content by digest, once per tenant (exists; variant models lifted, as today)
revision_references  what a revision refers to, extracted when it is stored (new)
revision_binaries → asset_content   bytes (exist)
```

**Writing a draft.** Saving builds the resource's content, computes its digest, stores it in
`resource_revisions` when it is new (the same content stays one row), moves the draft's entry to it
under the entry's `revision`, and stores the new revision's references once. Editing one variant
writes that variant's model and its template's small revision, not every variant.

**Operations become entry operations** (about one row per resource, no content):

| Operation                    | Today                               | Option 4                                                |
| ---------------------------- | ----------------------------------- | ------------------------------------------------------- |
| start a draft from a release | copy every content row              | copy its entries                                        |
| release the next release     | build a snapshot from the rows      | freeze the draft's entries under the released row       |
| what changed, diff           | rebuild and fingerprint the content | compare digests per entry                               |
| merge, fast-forward          | write content into rows             | point entries at digests (and a merger's new revisions) |

**Reading any release** is one path: a list reads entries only, with one indexed query; a resource
reads its entry, its revision and lifted children; usages and the graph join the release's entries to
`revision_references`; rendering already reads exactly this. One mapper per resource type turns a
revision into the domain model the pages use, for drafts and releases alike. Revisions never change,
so they cache perfectly by digest, across requests and nodes.

**At the scale above:** about 75 million release entries plus 1.5 million draft entries; every query
names a release, so an index on `(tenant_key, release_row_id, resource_type, resource_key)` keeps
reads selective (partitioning by tenant if needed). Revisions as today; drafts stop multiplying
content, because an open draft costs its entries. New: a garbage collector for revisions no entry
refers to (editor saves leave them behind), or keeping the last few per resource as undo history.

- **For:** one store and one read path for every version; drafts stop being full copies; copying,
  releasing, comparing and merging become cheap and exact (digests); the merge's inputs (ADR 0032 §5)
  stop needing fingerprints computed from rebuilt content.
- **Against:**
  - references between resources (a template's theme, an attribute's code list) are no longer
    foreign keys; integrity comes from checks at write time and the reference index, as the merge
    already checks;
  - querying fields means JSONB or summary columns in the entries; modules that query content tables
    directly (quality, load test) change;
  - every write path is rewritten (about 50 commands), each building valid content rather than
    updating a column, the contract's schema becoming what the column types were;
  - existing drafts move from rows into entries and revisions at an upgrade, data-preserving; the
    content tables exist on 1.x, so they are retired only after their data has moved.

**Phasing,** so it is never one large change:

1. The read interface of option 3: pages, MCP, REST and the editor read through it; the release
   implementation reads entries and revisions.
2. One mapper per resource type, shared by drafts and releases.
3. Drafts switch per type: themes first (simplest), then code lists, attributes, stencils, images and
   fonts, templates last (variants, the editor). Each switch replaces that type's draft
   implementation and its commands only.
4. A content table is retired once its type has moved; `catalog_versions` folds into
   `catalog_releases` (ADR 0032 §9) once no content row refers to it.

Option 3 is therefore not a detour but the first step of option 4.

### Supporting pieces, whichever option

- **A reference index per revision:** the resources a revision refers to, extracted when it is
  stored. Revisions are immutable, so it is computed once and shared by every release and draft that
  holds the revision; usages and the graph for any version become a join of its entries to it.
- **Release reads that do not load what they do not show:** a list reads entries (key, name, type)
  only; a resource reads its revision; binaries are read when shown, with an index on
  `revision_binaries (tenant_key, content_hash)`.
- **A slimmer `manifest_snapshot`:** catalog-level data only, the resource list being the entries.

## Decision

Open. The recommendation is **option 3 now, with option 4 as its natural continuation**, and option 2
only as an implementation detail of the release side where a view is the simplest correct query:

- it keeps the storage model that makes 2.5 million releases affordable, and its deduplication;
- it gives every surface one way to read a version, removing REST's duplicated paths rather than
  adding a third;
- the interface is exactly where option 4 later swaps the draft implementation, type by type, without
  touching the pages again (option 4's phasing).

Together with option 4 this completes the consolidation ADR 0032 §9 starts: one table of releases,
draft or released; one table of entries; one content store. The content tables and
`catalog_versions` then go.

Before committing to it, measure on a realistic data set: a release list and a resource read through
the release implementation, cold and cached, and the reference index's size per revision.

## Consequences (of option 3)

- A version reference that names a draft or a release (extending `CatalogVersionRef` / `DraftRef`,
  and the UI's `CatalogSegment`, `letters@<releaseId>`), with "released, read-only" enforced by the
  write guard.
- Read models per resource type, with a draft and a release implementation; REST, MCP, the UI and the
  editor read through them.
- The editor gains a read-only mode for templates.
- A reference index per revision, backfilled for existing revisions.
- The release read path stops assembling whole releases for lists.

## Related

- [ADR 0026](0026-revisions-releases-and-the-working-copy.md) (release content), [ADR 0032](0032-catalog-sources-releases-and-merging.md) (catalogs are drafts and releases; §9 folds `catalog_versions` into `catalog_releases`, which option 4 completes).
- [`docs/catalog-versioning.md`](../catalog-versioning.md), [`docs/catalog-workspace-navigation.md`](../catalog-workspace-navigation.md) (an earlier design of a
  catalog-and-version context).
