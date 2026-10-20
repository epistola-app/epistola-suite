# Version Axes

Epistola has several independent "version" concepts that are easy to conflate.
They answer **different questions** and mostly move independently. This page is
the map; each axis links to its detailed doc.

> **One-line summary.** Since 2.0 there are two: the **catalog release**
> versions content, and the wire-format `schemaVersion` versions _the shape of
> the exchange format_. Templates, data contracts and stencils have no versions
> of their own; they are edited in place and marked ready before a release.

## The axes

| Axis                                               | Versions…                                                   | Bumped when                           | Travels in export/import?                 | Used for                                                                       |
| -------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------ |
| **Catalog release version** (SemVer) + fingerprint | the whole catalog's _content_ at a point in time            | you cut a **release**                 | **Yes** — in the manifest `release` block | rendering, environments, upgrade detection across instances                    |
| **Catalog wire `schemaVersion`**                   | the _shape_ of the exchange format itself (one per catalog) | only when the JSON **format** changes | n/a — it _is_ the import/export layer     | forward-compatible import between instances on different **software** versions |

## What each axis is for

- **Catalog release version + fingerprint** — _"which release of this
  catalog?"_ It is the only content version: generation renders a release, and
  another instance that subscribed to your catalog can detect that something
  changed and upgrade. The SemVer is the human-declared release; the
  fingerprint is the content identity that decides "did anything actually
  change?". See [catalog-versioning.md](catalog-versioning.md).
- **Wire-format `schemaVersion`** — _"what shape is the exchange file?"_ Purely
  so an import works between instances running different **software** versions.
  It changes **only when the format changes**, never when your content changes.
  There is **one catalog-wide version** for the whole bundle — the manifest is
  authoritative and every resource detail echoes the same number, so the catalog
  moves together. See [ADR 0007](adr/0007-catalog-wire-format-migrations.md) and
  [exchange/README.md](exchange/README.md#wire-format-version-gate).

## What is not a version

Before 2.0 a template variant had draft and published **template versions**, a
template had **contract versions** of its data contract, and a stencil had
**stencil versions** that template instances pinned. Contract ADR 0002 removed
all three:

- a variant has one working model and a template one data contract, edited in
  place;
- a stencil has one working copy, and instances reference it by key
  ([stencils.md](stencils.md));
- a **ready mark** records that a resource's current content is reviewed; a
  release is refused while a changed resource is not ready
  ([catalog-versioning.md → Ready marks](catalog-versioning.md#ready-marks)).

The old version tables stay in the database as history nobody reads.

## How they relate (and don't)

- **Release version vs `schemaVersion` are orthogonal.** A catalog can publish
  release `5.9.0` (content) at wire `schemaVersion` 4 (format); editing content
  bumps the release/fingerprint but not the `schemaVersion`, and a format change
  bumps the `schemaVersion` but not the release. The fingerprint **excludes**
  `schemaVersion` by construction, so a wire-format migration is fingerprint-
  transparent (see [catalog-versioning.md](catalog-versioning.md#fingerprint-algorithm)).
- **A below-current `schemaVersion` is handled differently per catalog type and
  transport.** A SUBSCRIBED mirror is never migrated locally (its source must
  republish — the catalog list flags this as _out of sync_); an AUTHORED ZIP is
  blocked when no migration path exists, or prompts for confirmation when one
  does (migrating mutates the imported content). See
  [exchange/README.md → Import action for a below-current payload](exchange/README.md#import-action-for-a-below-current-payload)
  and [Subscribed source schema sync](exchange/README.md#subscribed-source-schema-sync).

## See also

- [catalog-versioning.md](catalog-versioning.md) — release SemVer + content fingerprint, upgrade flow.
- [data-contracts.md](data-contracts.md) — a template's data contract and its compatibility checks.
- [exchange/README.md](exchange/README.md) — the wire format, resource contracts, and the version gate.
- [ADR 0007](adr/0007-catalog-wire-format-migrations.md) — catalog-wide wire-format migrations.
- [ADR 0003](adr/0003-stencil-version-in-export.md) — stencil version on the wire (before 2.0).
