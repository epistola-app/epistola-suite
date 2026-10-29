# ADR 0042: Organising catalogs (relocation in 2.0)

- **Status:** Proposed
- **Date:** 2026-10-29
- **Deciders:** Epistola team
- **Tags:** catalog, relocation, refactoring, drafts, releases, dependencies
- **Amends:** [ADR 0037](0037-resource-identity-and-relocation.md) ("Relocation is a crude move")
- **Related:** [ADR 0038](0038-catalogs-in-2-0.md), [ADR 0039](0039-catalog-knows-no-content-type.md);
  contract ADR 0002 (versions), contract ADR 0003 (dependencies)

## Context

Relocation exists for two things people really do:

1. **Fix a mistake.** A template, theme or stencil was created in the wrong catalog, or under the
   wrong key. Usually it has never been released there.
2. **Create a shared catalog later.** Themes, stencils and fonts that grew in one catalog, sometimes
   in several near-identical copies, are wanted by others, so they go to a catalog of their own that
   the others depend on. These resources are released, and used.

What both need is not moving content but keeping what uses it working: every reference must mean
the same resource after its address changes. It is a refactoring, as an IDE's rename or move is,
not a data move.

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

Meanwhile 2.0 already has what such a refactoring needs:

- **A draft may depend on another catalog's draft** (`CatalogDependencyDeclaration.namesDraft`), and
  releasing it with `releaseDependencies` releases those drafts first, by their own rules, and pins
  the releases they become (`ReleaseCatalogDraft.releaseDependencies`).
- **Change kinds** say what a draft's changes mean for its version number (`ChangeKindEvaluator`,
  `NextVersionChanges`).
- **A generic view of content**: every resource is stored alike, its references are indexed
  (`resource_references`), and each type says what is particular to it (ADR 0039).

## Decision

Relocation is redone as **refactorings of catalogs**, on one core operation, with their
consequences for releases shown before anything happens.

### The core operation: re-point references

In one draft of each catalog involved, every reference to address A becomes a reference to address B:

- **One draft per catalog.** In the catalog a refactoring starts from, the draft the person is
  working in, whichever it is: any draft can be refactored, a release cannot. In every other catalog
  involved (the target, and the catalogs whose content names the resource), its everyday draft (the
  next version), where ongoing work lands. A catalog's other drafts (parallel work, a hotfix) are
  left entirely alone, the resource and its references both, so each stays a coherent snapshot at
  the old address; they get the change when a release made after it is merged into them (below).
  Forcing it into a hotfix draft would make a small fix a breaking change with a new dependency.
  Released content is never touched.
- A reference is rewritten where `ResourceReferenceSites` finds it, or by its type for the ones the
  type carries itself (a template's theme and variant attributes: `ResourceType.references` gains a
  rewrite).
- A draft that now names a catalog it did not use gets a **dependency on that catalog's draft**, so
  it keeps working before that catalog has a release, and releasing it can release that catalog first.

### The refactorings

Each is the core operation combined with ordinary draft edits, in one transaction:

| Refactoring               | What it does                                                                       | Identity |
| ------------------------- | ---------------------------------------------------------------------------------- | -------- |
| **Rename**                | copies the resource to its new key in the same catalog, re-points, removes the old | kept     |
| **Move to catalog**       | copies it into the target's draft, re-points, removes it from the source's draft   | kept     |
| **Create shared catalog** | creates a catalog and moves a set of resources into it                             | kept     |

Moving content into another catalog re-expresses its own references: one to a resource that moves
along stays relative, one to a resource left behind names its catalog by alias. A template moves
with its variants.

The resource leaves its source: a refactoring does not keep a copy behind. For a published source
that is a breaking change for those who installed it, and the preview says so (below).

Two refactorings are left out until someone needs them, each the same core operation composed
differently: **keeping the source's copy** while everything else follows (for a published catalog
that must not break), and **merging** copies of one resource from several catalogs into one (re-point
the copy's users, remove the copy).

### Identity follows the move

A resource's identity (`resource_id`, ADR 0037) is what generation history, quality findings and
load-test runs hang on, and an identity has one current address. The resource the refactoring
changes keeps it: its history follows it to its new address. A copy left behind in another draft of
its catalog stays at the old address and gets an identity of its own there, with what that draft
keeps of it (its ready marks, its default theme). Its content and address do not change, and the
releases it came from keep the identity they were cut with.

The registry does this where it already keeps identities (`sync_draft_resource_identity`, by
`split_draft_resource_identity`), so a move or rename while other drafts hold the resource is no
longer refused.

### Merging after a refactoring

Merging a release into a draft (contract ADR 0005) matches resources by type and key. After a
refactoring, a release made from a draft left alone (a hotfix) still names the old address, so a
merge would read the moved resource as removed by the draft, refuse a reference to the old address,
or bring the old copy back.

A merge **deduces** a move instead of recording one: a release's rows keep the identity they were cut
with, so the merge base says which identity was at the old address, and the registry says where that
identity is now. When it is at another address in this installation, the resource moved:

- a reference in what the merge takes to the old address is re-pointed to the new one, as the
  refactoring re-pointed the draft's own;
- a change the release made to the moved resource is a conflict that says where the resource went,
  for the author to apply there or leave.

Nothing is stored for it and the contract does not change. A release says what it holds, not how
it came to be, and identities are local: in another installation a move stays a removal and an
addition (ADR 0037), merged as such.

### What people see

The word "refactoring" stays in code and docs. People see actions named for what they do, on the
**Organise** page (the one the resource graph already hands off to) and on a resource:

- **Rename…**
- **Move to catalog…**
- **Create a shared catalog…** (choose resources, possibly from several catalogs, and a name)

Every preview opens with **"Everything that uses it will be updated"**, then says what that takes.

### The preview is the change, rolled back

The preview runs the same transaction and rolls it back, so it shows exactly what applying does.
Per affected draft:

- what is added, removed and re-pointed;
- the change kind against that draft's base (`ChangeKindEvaluator`): "the next release of Letters is
  a major: it no longer contains the theme `house`"; "Shared gains three resources"; "Invoices now
  depends on Shared";
- for a published source, plainly: installations that installed it must adapt when they take that
  major release;
- the release order this implies (a catalog before those that now depend on it), with **Release
  with its dependencies** as the one action that carries it out;
- where the old address lives on: the source's releases, and every catalog's other drafts, named
  ("Hotfix 1.4.1 of Letters still uses `house`; it is not changed");
- what stops resolving: released content and other installations that name the old address
  (unchanged from ADR 0037: they cannot follow).

### How it is built

Generically, on ADR 0039, replacing the per-type planner and relocators:

- the references to re-point come from `resource_references`, in the one draft of each catalog, by
  alias and key;
- content is stored through one writer of a version's resources (`VersionResources.put`,
  ADR 0039 §2, built for this), guarded by the content hash the plan saw;
- a type stored as parts (a template and its variants) moves with its parts;
- re-pointing declares the dependencies it needs, naming the target's draft;
- the drafts the operation changes come from the message targeting the draft worked in
  (`CatalogReleaseScoped`): its own catalog reads and writes that draft, every other catalog its next
  version, which is the rule above.

**Create a shared catalog** creates the catalog first, as its own step, and then moves the
selection into it: the move's preview needs the catalog to exist, and an empty catalog left behind
when the move is not applied costs nothing worth a combined transaction.

It leaves alpha: Stable and on by default, `resource-relocation` kept as the switch that turns it off
for a tenant. It is a UI operation, not a REST or MCP one, so no GA API carries it. When the new
engine lands, the old one is removed in the same change: the old planner, the `ResourceRelocator`s and
their rewrites go; the move messages stay.

## Consequences

- Both use cases get a path that breaks no one by surprise: a mistake is a rename or a move with
  its consequences shown; a shared catalog is created and the release order carried out by the
  release flow that exists.
- One core operation serves every refactoring, so they cannot disagree on what "updated" means, and
  a new one (keeping the source's copy, say) is a small composition.
- The relocation code becomes generic: no per-type planner or relocator, and the SQL-level type
  knowledge in the planner goes (ADR 0039, "Where it landed").
- `VersionResources.put` is built, which ADR 0039 left for later.
- Following a refactoring across installations is still not offered.

## Settled

On 2026-10-29: a refactoring changes one draft per catalog, any draft in the catalog it starts from,
and leaves every other draft alone; keeping the source's copy and merging copies are left out; the
old implementation is removed when the new one lands; the identity follows the move and a copy left
behind gets one of its own; a merge deduces a move from identities, with nothing recorded and no
change to the contract.
