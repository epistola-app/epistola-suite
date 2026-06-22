# Version Axes

Epistola has several independent "version" concepts that are easy to conflate.
They answer **different questions** and mostly move independently. This page is
the map; each axis links to its detailed doc.

> **One-line summary.** Your template draft→publish version is _content history
> inside your instance_; the import/export `schemaVersion` is _the shape of the
> exchange format_. They are separate — the only bridge is the **stencil
> version**, which travels as data on the wire.

## The axes

| Axis                                               | Versions…                                                   | Bumped when                           | Travels in export/import?                                              | Used for                                                                        |
| -------------------------------------------------- | ----------------------------------------------------------- | ------------------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| **Template version** (per variant)                 | the _content_ of a template variant                         | you **publish** a draft               | **No** — export carries the latest published _content_, not the number | history & rollback, deploy a specific version to an environment, pinning        |
| **Contract version**                               | a template's data contract (JSON Schema)                    | you publish a contract draft          | the schema _content_ travels; its own draft/publish lifecycle          | breaking-change detection — what data a template accepts                        |
| **Stencil version**                                | a stencil's published _content_                             | you publish a stencil version         | **Yes** — `StencilResource.version` is on the wire and preserved       | templates **pin** a specific stencil version; the pin must survive a round-trip |
| **Catalog release version** (SemVer) + fingerprint | the whole catalog's _content_ at a point in time            | you cut a **release**                 | **Yes** — in the manifest `release` block                              | upgrade detection across instances (subscribers see "a newer version")          |
| **Catalog wire `schemaVersion`**                   | the _shape_ of the exchange format itself (one per catalog) | only when the JSON **format** changes | n/a — it _is_ the import/export layer                                  | forward-compatible import between instances on different **software** versions  |

## What each axis is for

- **Template / contract / stencil version** — _"which iteration of this
  resource's content?"_ Editorial history within your instance: roll back, keep
  an audit trail, publish a specific version to an environment, and let other
  things reference (pin) a fixed point. See
  [schema-versioning.md](schema-versioning.md) for the contract draft/publish
  lifecycle and compatibility checks.
- **Catalog release version + fingerprint** — _"which release of this
  catalog?"_ So another instance that subscribed to your catalog can detect that
  something changed and upgrade. The SemVer is the human-declared release; the
  fingerprint is the content identity that decides "did anything actually
  change?". See [catalog-versioning.md](catalog-versioning.md).
- **Wire-format `schemaVersion`** — _"what shape is the exchange file?"_ Purely
  so an import works between instances running different **software** versions.
  It changes **only when the format changes**, never when your content changes.
  There is **one catalog-wide version** for the whole bundle — the manifest is
  authoritative and every resource detail echoes the same number, so the catalog
  moves together. See [ADR 0007](adr/0007-catalog-wire-format-migrations.md) and
  [exchange/README.md](exchange/README.md#wire-format-version-gate).

## How they relate (and don't)

- **Template publish is separate from import/export.** Publishing a template
  bumps its variant version inside your instance. On export only the **latest
  published content** is taken — the version _number_ does not travel, and the
  importing instance assigns its own. So a template at "v7" here does not arrive
  as "v7" elsewhere.
- **Stencils are the one bridge.** A template node pins a stencil version
  (`props.version`), so that version **number** is real, user-visible data and
  must survive a round-trip. The wire format therefore carries
  `StencilResource.version` and the importer installs at that number (see
  [ADR 0003](adr/0003-stencil-version-in-export.md)). The stencil-shape change
  that made that field required is the motivating example for the wire-format
  migration machinery ([ADR 0007](adr/0007-catalog-wire-format-migrations.md)) —
  a _format_ change carrying a _content_ version.
- **Release version vs `schemaVersion` are orthogonal.** A catalog can publish
  release `5.9.0` (content) at wire `schemaVersion` 4 (format); editing content
  bumps the release/fingerprint but not the `schemaVersion`, and a format change
  bumps the `schemaVersion` but not the release. The fingerprint **excludes**
  `schemaVersion` by construction, so a wire-format migration is fingerprint-
  transparent (see [catalog-versioning.md](catalog-versioning.md#fingerprint-algorithm)).

## See also

- [catalog-versioning.md](catalog-versioning.md) — release SemVer + content fingerprint, upgrade flow.
- [schema-versioning.md](schema-versioning.md) — contract (data-model) draft/publish + compatibility.
- [exchange/README.md](exchange/README.md) — the wire format, resource contracts, and the version gate.
- [ADR 0007](adr/0007-catalog-wire-format-migrations.md) — catalog-wide wire-format migrations.
- [ADR 0003](adr/0003-stencil-version-in-export.md) — stencil version on the wire.
