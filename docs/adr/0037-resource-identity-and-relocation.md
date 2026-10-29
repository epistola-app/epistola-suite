# ADR 0037: Resource identity and relocation

- **Status:** Accepted — implemented (2.0)
- **Date:** 2026-10-27
- **Deciders:** Epistola team
- **Tags:** catalog, resources, identity, relocation
- **Supersedes:** [ADR 0014](0014-safe-catalog-resource-relocation.md),
  [ADR 0020](0020-where-a-catalog-resource-address-lives.md),
  [ADR 0024](0024-where-a-catalog-resource-identity-is-allocated.md),
  [ADR 0025](0025-relocation-without-aliases.md)
- **Related:** [ADR 0038](0038-catalogs-in-2-0.md) (where resources are stored),
  contract ADR 0004 (resource identity within a catalog)

## Context

Four ADRs grew one model in steps: 0014 separated a resource's identity from its address and
designed relocation with aliases; 0020 kept the address on the resource row; 0024 proposed who
allocates an identity; 0025 withdrew aliases before any release carried them and made relocation a
crude move. 2.0 then moved every resource into JSON storage
([ADR 0038](0038-catalogs-in-2-0.md)), so the per-type tables those ADRs talk about are gone. This
record states the model as it holds now, in one place.

## Decision

### Address and identity

- A resource's **address** is its catalog and key: `(catalog, resource type, resource key)`, the
  public name a reference, an export and the API use. A catalog itself is addressed by registry,
  namespace and key, and stored by `catalog_id` (ADR 0034, still current for names).
- A resource's **identity** is its `resource_id`, a UUIDv7 that never changes for the life of the
  resource, whatever its address. `catalog_resources` registers it, keyed by tenant, and holds the
  address it currently has.
- Identity is registered by the draft row that holds the resource: inserting a draft row in
  `release_resources` adopts the identity registered at its address or registers a new one; moving
  or renaming the row carries the identity along; deleting the last row that holds it removes it
  (`sync_draft_resource_identity`). Released rows are frozen and never touch the registry.
  Variant rows (`parent_id` set) have no identity of their own.
- What must survive a rename or a move refers to the identity, by typed foreign key: generation
  history (`template_resource_id`), quality findings and ignores, load-test runs. Content refers to
  other resources **by address**, as the wire form does: alias and key, indexed in
  `resource_references` (ADR 0038). Content never stores an identity.

### Relocation is a crude move

> Amended by [ADR 0042](0042-reorganising-catalogs.md) (proposed): in the 2.0 model a move changes one
> draft, not a catalog, and its consequences for releases are invisible here. Relocation is redone
> as refactorings of drafts (rename, move, create a shared catalog) whose preview shows change kinds and release order.

- Relocation moves a resource to another catalog, renames its key, or both, in catalogs that can be
  changed here. It is alpha, off by default, behind `resource-relocation`
  ([catalog resource relocation](../catalog-resource-relocation.md)).
- A move keeps the identity, rewrites the references in **drafts** that name the moved resource
  (through `resource_references` and the typed reference sites: templates, stencils, themes,
  attribute code-list bindings, theme styles), and moves template variants with their template.
  Released content is never rewritten: a release keeps naming the address it was cut with.
- Nothing is left at the old address. Anything that still names it, outside the rewritten drafts,
  stops resolving, and the address can be reused at once. There are no aliases.
- A preview is required before a move and lists what will be rewritten and what will break.

## Consequences

- One identity model for every resource type, enforced by the registry trigger and by
  `FrozenVersionWriteGuardTest` for writes to version rows.
- Following a publisher's move across installations is not offered: a received release names
  addresses, and a renamed resource is a new address there.
- Making relocation safe enough to leave alpha needs no alias mechanism: drafts follow a move, and
  releases are immutable and self-contained (ADR 0038).

## Alternatives considered

The options weighed in ADRs 0014, 0020, 0024 and 0025 — rewriting published content, refusing moves
with references, copying instead of moving, aliases left at the old address, the address on a
separate table — stand as recorded there. The aliases were the decisive one: withdrawn in ADR 0025
before any release carried them, because they made a published reference depend on local state.
