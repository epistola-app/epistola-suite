# ADR 0040: Storage and contracts

- **Status:** Proposed
- **Date:** 2026-10-28
- **Deciders:** Epistola team
- **Tags:** catalog, storage, contract, rest-api, wire-format, migrations
- **Related:** [ADR 0038](0038-catalogs-in-2-0.md) (one storage, in the wire form),
  [ADR 0039](0039-catalog-knows-no-content-type.md) (the catalog knows no content type),
  [ADR 0035](0035-data-migrations-and-skipping-upgrades.md) (code migrations); contract ADR 0003
  (fingerprints)

## Context

A catalog resource has several shapes, from three sources:

| Shape                                                         | From               | Used for                                                                              |
| ------------------------------------------------------------- | ------------------ | ------------------------------------------------------------------------------------- |
| Template model (`TemplateDocument`, styles, page settings)    | contract           | editor, renderer, storage, archive, REST; the suite's own type is a `typealias` to it |
| Catalog wire form (`ThemeResource`, `TemplateResource`, …)    | contract           | archives, fingerprints, and since ADR 0038 storage                                    |
| REST models (`ThemeDto`, `TemplateDto`, …)                    | contract (OpenAPI) | the external REST API, mapped by hand from domain objects in `rest-api`               |
| Domain read models (`Theme`, `StoredTemplate`, `ThemeFields`) | suite              | UI, commands, rendering                                                               |
| Envelope settings (a code list's `source`)                    | suite              | suite-only data beside the wire form in a stored content                              |

All three contract artifacts ship under one version (`2.0.0-alpha.3`).

Storing the wire form (ADR 0038) and reading it generically (ADR 0039) tie storage to a public
format: the stability contract names catalog wire formats as a GA surface under SemVer. That tie
has consequences nobody wrote down:

- **Every content feature is contract-first.** A field the suite wants to store must be in the
  wire form first, or it is an envelope setting and never travels.
- **Stored content records no wire version.** It is implicitly the current one; export stamps
  `CATALOG_SCHEMA_VERSION` on it. Archives are upgraded on import (`CatalogSchemaMigrator`), stored
  content is not, so the next wire version has no path for what is stored.
- **REST describes the same resource in another shape.** Compared field by field (contract
  `feature/v2`, `resource-detail-v8` against `openapi.yaml`, main DTO per type):

  | Type      | Wire only                                                                                               | REST only                                                                      |
  | --------- | ------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
  | Theme     | `defaultBlockStyles`, `key`, `metadata`, `tags`                                                         | `createdAt`, `id`, `lastModified`, `tenantId`                                  |
  | Stencil   | `content`, `key`, `metadata`, `parameterSchema`, `readsTemplateData`                                    | `createdAt`, `id`, `lastModified`, `tenantId`, `versions`                      |
  | Font      | `key`, `metadata`, `tags`                                                                               | `catalog`, `catalogType`, `createdAt`, `lastModified`, `readOnly`, `slug`      |
  | Code list | `entries`, `key`, `metadata`, `name`, `tags`                                                            | `displayName`, `slug`, `sourceType`, `sourceUrl`, `authType`, refresh state, … |
  | Attribute | `metadata`, `name`, `tags`                                                                              | `catalog`, `catalogType`, `createdAt`, `description`, `displayName`, …         |
  | Template  | `description`, `key`, `metadata`, `pdfaEnabled`, `templateModel`, `themeKey`, `themeCatalogKey`, `tags` | `createdAt`, `id`, `lastModified`, `schema`, `tenantId`                        |
  | Image     | `contentHash`, `contentUrl`, `key`, `metadata`, `tags`                                                  | `catalog`, `catalogType`, `createdAt`, `id`, `mediaCategory`, `sizeBytes`, …   |

  Some of the wire-only members are served by other REST operations (a stencil's content, a
  template's variants and models); that was not checked member by member. Still: the same identity
  is `key`, `id` or `slug`, the same label `name` or `displayName`, and `tags` and `metadata` are
  in no REST resource. Nothing checks that the two stay in step.

- **Fingerprints survive a re-encoding.** Contract ADR 0003: the V6 fingerprint does not cover the
  wire version (`schemaVersion`); "a wire version that only re-encodes content keeps every V6
  value".

## Decision

### 1. The wire form is the storage form

ADR 0038 stands. A release must keep the form it was published and fingerprinted in, so releases
need the wire form whatever drafts use, and one form means no translation that can drift.

Its consequence becomes a rule: **content is contract-first.** A member a resource carries is
added to the contract's wire form (and the template model where it applies) before the suite stores
it. What only this suite keeps goes beside the released form in the envelope, never inside
`resource`, and so never travels. `catalog/AGENTS.md` says so, and `StoredContentShapeTest` already
refuses a content outside the envelope.

### 2. Stored content records its wire version, and is migrated once

- Every stored content records the wire version it is in. Content stored before the column exists
  is the current version (v8).
- A new wire version comes with a **migration of stored content**, run once at upgrade (a code
  migration, ADR 0035): each content is migrated to the new version and stored as a new content,
  the current-version copy that rendering, editing and export read. Nothing migrates on read.
- **The published form is kept.** A content stays as it was stored; the migration adds its copy.
  A received release keeps the bytes it was published in, so it can be re-exported byte for byte.
- **A wire migration is a re-encoding.** It must keep every V6 fingerprint; the migration
  recomputes V6 for each release before and after and fails on a difference. A change that alters
  what a resource renders is not a wire migration: it is a new release, made by an author.

Keeping the published form is needed only for a byte-identical re-export: V6 itself would survive
the copy alone. If byte-identical re-export turns out not to be wanted, the copy can replace the
original instead; that is a later, cheaper choice, not a different design.

### 3. REST: a drift check now, one shape later

- **Now:** a test per resource type holds, for every member of the wire resource, that REST serves
  it (under its REST name) or that a ledger says why not. The ledger may only shrink, like
  `CatalogBoundaryTest.ALLOWED`. It makes the mapping in `rest-api` visible, and a new wire member
  fails until REST serves it or the ledger names it.
- **Next major:** REST serves a resource in its wire shape, through one generic resource endpoint
  per catalog version (`…/versions/{version}/resources/{type}/{key}`) that ADR 0039's `ResourceType`
  makes possible, with server state (`createdAt`, read-only, refresh state) beside it rather than
  mixed in. That is a breaking REST change and waits for a major release.

## Consequences

- One rule decides where a new member goes: in the contract if it travels, in the envelope if it
  does not. The suite cannot store a travelling member the contract does not have.
- The next wire version costs a migration of stored content, with a fingerprint check, on top of
  the archive migrator. It is written once per wire version, not once per read.
- Storage grows by one content per migrated resource while the published form is kept.
- REST's divergence is measured and can only shrink, until the next major removes the second shape.

## Open questions

1. **One contract version for three artifacts.** The template model, the wire form and the REST
   API ship under one version number, though they change at different speeds: a breaking wire
   change is a contract major, which reads as a REST major too. Should the contract version them
   separately?
2. **The REST names.** `key`/`id`/`slug` and `name`/`displayName` for the same thing: settle on the
   wire's names in the next major, or keep REST's?

## Order

1. This ADR, and the contract-first rule in `catalog/AGENTS.md`.
2. The REST drift test with its ledger.
3. The wire-version column on stored content (a forward migration, no data change: every row is v8).
4. The stored-content migration and its fingerprint check, with the next wire version.
