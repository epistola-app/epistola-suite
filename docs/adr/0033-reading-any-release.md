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

Drafts become entries pointing at revisions too, like releases (git's index: blobs, a tree per
version); saving writes a new revision and moves the draft's entry. The content tables either go or
become a derived working form.

- **For:** one store and one read path for every version; copying a draft, releasing it and
  comparing versions become entry operations (a release is freezing a draft's entries); drafts stop
  being full copies; a merge or diff compares digests.
- **Against:** every write path changes; relational features the content tables give (foreign keys,
  column indexes, per-row revisions for optimistic locking) move into derived structures (a reference
  index, a name index, a revision per entry); unreferenced revisions need garbage collection. The
  largest change, best done once the read side is unified.

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
- the interface is exactly where option 4 later swaps the draft implementation, without touching the
  pages again.

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

- [ADR 0026](0026-revisions-releases-and-the-working-copy.md) (release content), [ADR 0032](0032-catalog-sources-releases-and-merging.md) (catalogs are drafts and releases).
- [`docs/catalog-versioning.md`](../catalog-versioning.md), [`docs/catalog-workspace-navigation.md`](../catalog-workspace-navigation.md) (an earlier design of a
  catalog-and-version context).
