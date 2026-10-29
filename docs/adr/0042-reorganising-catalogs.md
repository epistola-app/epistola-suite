# ADR 0042: Reorganising catalogs (relocation in 2.0)

- **Status:** Proposed
- **Date:** 2026-10-29
- **Deciders:** Epistola team
- **Tags:** catalog, relocation, drafts, releases, dependencies
- **Amends:** [ADR 0037](0037-resource-identity-and-relocation.md) ("Relocation is a crude move")
- **Related:** [ADR 0038](0038-catalogs-in-2-0.md), [ADR 0039](0039-catalog-knows-no-content-type.md);
  contract ADR 0002 (versions), contract ADR 0003 (dependencies)

## Context

Relocation exists for two things people really do:

1. **Fix a mistake.** A template, theme or stencil was created in the wrong catalog. Usually it has
   never been released there, or only in a catalog nobody else uses yet.
2. **Create a shared catalog later.** Themes, stencils and fonts that grew in one catalog are wanted
   by others, so they move to a catalog of their own that the others depend on. These resources are
   released, and used.

Relocation was designed when a catalog had one working copy. ADR 0037 carried it into 2.0 as a
crude move: one tenant-wide edit that keeps the resource's identity, rewrites the references in
drafts and leaves nothing at the old address. In the 2.0 model, where a catalog is a set of
versions (frozen releases and possibly several drafts), that is not what happens:

- **It changes one draft of the source, not the catalog.** The resource is taken out of the
  source's current draft. Its releases keep it, and so do its other drafts: the old address lives on.
- **It reads only current versions.** The planner reads the `current_*` views, so references in a
  catalog's other drafts are not re-pointed (as read in the code; not tested).
- **Its consequences for releases are invisible.** The source's next release loses a resource, which
  is breaking (a major version). A catalog whose content now names the target gets a new dependency,
  and cannot release until the target has a release that holds the resource. The preview shows
  none of it.
- **Published catalogs break their subscribers.** A resource moved out of a published catalog is
  removed for everyone who installed it, and they cannot follow the move (ADR 0037).

Meanwhile 2.0 already has what a reorganisation needs:

- **A draft may depend on another catalog's draft** (`CatalogDependencyDeclaration.namesDraft`), and
  releasing it with `releaseDependencies` releases those drafts first, by their own rules, and pins
  the releases they become (`ReleaseCatalogDraft.releaseDependencies`).
- **Change kinds** say what a draft's changes mean for its version number (`ChangeKindEvaluator`,
  `NextVersionChanges`).
- **A generic view of content**: every resource is stored alike, its references are indexed
  (`resource_references`), and each type says what is particular to it (ADR 0039).

## Decision

Relocation is redone as a **reorganisation of drafts**, built from those parts, with its
consequences for releases shown before anything happens.

### What it does

A reorganisation takes a set of resources, from one or more source catalogs, to one target catalog
(an existing one that can be changed here, or a new one created in the same step), each keeping or
changing its key. In one transaction:

1. **The target's draft gets each resource**, its content re-expressed for the target: a reference to
   a resource that moves along stays relative, one to a resource left behind names its catalog by
   alias.
2. **The source's draft loses it**, unless the reorganisation **keeps it in the source** (see below).
3. **Every draft of the tenant that names a moved resource is re-pointed** at its new address: every
   draft, not only each catalog's current one. Released content is never touched.
4. **Each draft that now names the target depends on the target's draft** (a declaration naming that
   draft), so it keeps working before the target has a release, and releasing it can release the
   target first.

### Two modes, chosen per reorganisation

- **Move** (the default): the resource leaves the source. The moved resource keeps its identity, so
  what refers to its identity (generation history, quality findings) follows it. Right for a
  mistake, and for a source nobody outside the tenant uses.
- **Keep in source**: the source keeps its copy, unchanged, and everything else is re-pointed at the
  target, whose copy gets a new identity. Nothing breaks for anyone who installed the source; its
  copy can be removed later, deliberately, in a major release. Right for a published source.

The preview recommends one: move for a resource the source has never released, keep in source for
one it has released and published.

### The preview is the change, rolled back

The preview runs the same transaction and rolls it back, so what it shows is what applying would do.
It lists, per affected draft:

- what is added, removed and re-pointed;
- the change kind against that draft's base, from `ChangeKindEvaluator`: the source's next version
  is a major when a released resource leaves it, the target's gains resources, a re-pointed draft
  gains a dependency;
- the release order this implies (the target before every draft that now depends on it), with
  "release with its dependencies" as the one action that carries it out;
- where the old address lives on: the source's releases, and the source's other drafts, which a
  reorganisation does not change (it names them so the person can decide);
- what will stop resolving: released content and other installations that name the old address
  (unchanged from ADR 0037: they cannot follow).

### How it is built

Generically, on ADR 0039, replacing the per-type planner and relocators:

- the references to re-point come from `resource_references`, every draft, by alias and key;
- a reference is rewritten where `ResourceReferenceSites` finds it, or by the type for the ones it
  carries itself (`ResourceType.references` gains a rewrite: a template's theme and variant
  attributes);
- content is stored through one writer of a version's resources (`VersionResources.put`,
  ADR 0039 §2, built for this), guarded by the content hash the plan saw;
- a type stored as parts (a template and its variants) moves with its parts.

It stays alpha, behind `resource-relocation`, until the new one replaces the old; the old planner,
the `ResourceRelocator`s and the move command go with it.

## Consequences

- A reorganisation says what it does to every version it touches, and the release flow carries it
  out with the machinery that exists: draft-naming dependencies, `releaseDependencies`, change kinds.
- Both use cases get a path that does not break anyone by surprise: a mistake is a move with no
  release consequence; a shared catalog is a move or a copy whose consequences are shown and ordered.
- The relocation code becomes generic: no per-type planner or relocator, and the SQL-level type
  knowledge in the planner goes (ADR 0039, "Where it landed").
- `VersionResources.put` is built, which ADR 0039 left for later.
- Following a move across installations is still not offered.

## Open questions

1. **The source's other drafts.** A reorganisation changes the draft it is given. Should it refuse,
   warn, or offer to apply the same change to the source's other drafts that hold the resource?
2. **Keep-in-source identities.** The target's copy gets a new identity, so generation history stays
   with the source's copy. Is that right for templates, or should the identity go with the copy that
   is used from now on?
3. **A new target catalog.** Created in the same step (registry, namespace, key, name), or required
   to exist first?
4. **The old implementation.** Removed when the new one lands, or kept off behind its toggle for one
   release?
