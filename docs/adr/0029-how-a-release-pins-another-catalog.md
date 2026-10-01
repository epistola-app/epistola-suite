<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0029: How a release pins another catalog

- **Status:** Proposed
- **Date:** 2026-10-01
- **Deciders:** Epistola team
- **Tags:** catalog, releases, dependencies, upgrades, generation
- **Related:** [ADR 0026](0026-revisions-releases-and-the-working-copy.md) (§6, how a reference
  resolves), [the 2.0 release model plan](../catalog-release-model-v2.md) (D14),
  [#1047](https://github.com/epistola-app/epistola-suite/issues/1047) (stencils by revision),
  [#1057](https://github.com/epistola-app/epistola-suite/pull/1057) (the first implementation)

## Context

A catalog does not depend on another catalog by declaration. **Its resources do.** A template uses a
theme from another catalog, a stencil inserts content from one, a theme names a font that lives in a
third, an image node points at a shared logo. A catalog depends on catalog B because, and only
because, some of its resources reference resources in B.

Generation renders releases only (#1057). So when release `letters@1.0.0` is rendered and one of its
templates uses B's theme, something must say **which release of B** that theme is read from. Reading
B's working copy is ruled out: an edit in B would change what an existing release of `letters`
renders. Reading B's latest release is ruled out for the same reason one step later.

#1057 implements the simplest answer, decision D14 in the plan: **when a catalog is released, every
other catalog it references is pinned at that catalog's latest release**, recorded in
`release_dependencies` as one row per `(release, dependency catalog)`. A release of `letters` uses
exactly one release of B.

Two situations test that answer.

1. **A new system catalog.** A suite upgrade ships `system@1.4.0`. Every existing release keeps
   `system@1.3.0` and keeps rendering as it was approved. The next release of a catalog adopts 1.4.0
   automatically, because pinning takes the latest. That is correct, but silent, and all or nothing:
   one release of `letters` cannot move half its templates.
2. **A large catalog trying an upgrade on a few templates first.** A catalog with 100 templates
   wants five of them on `B@2.0.0` while the other 95 stay on `B@1.0.0` until the new release of B
   has proven itself. One pin per catalog cannot express that inside one release of the catalog.

The question this ADR records: **at what granularity does a release pin another catalog — the
catalog, the template, or each reference?** It is a question about the pin, not about where
dependencies come from; those always come from resource references.

### What has to hold whatever is chosen

- **A release means one fixed thing.** Rendering a given release twice reads the same content,
  whatever happened to other catalogs since.
- **A document is internally consistent.** Within one generated document, the resources of one other
  catalog come from one release of it. A stencil from `B@1` that names style presets the theme from
  `B@2` no longer defines is the failure to avoid — mixing _within_ a document, not across templates.
- **References inside a catalog resolve in the release being resolved** (ADR 0026 §6, first row). A
  stencil taken from `B@1` brings its own fonts and images from `B@1`, whichever pins the consumer
  holds. This is what makes a pinned subtree self-contained.
- **Released content is retained while anything pins it.** Deleting or forgetting a pinned release is
  refused.

## Considered options

### A. One pin per catalog (what #1057 built)

Each release of `letters` pins each other catalog it uses exactly once, at that catalog's latest
release when `letters` is released.

- **What a release means:** one tested combination. `letters@1.1.0` is "these templates, with
  `system@1.4.0` and `shared@2.0.0`". Easy to state, show and support.
- **Upgrading:** one decision per dependency catalog, taken by releasing. The release dialog can show
  "system: 1.3.0 → 1.4.0, affects these templates" and run the compatibility check of #1047 against
  every affected template at once.
- **Consistency within a document:** guaranteed, since all of B comes from one release.
- **Retention:** the fewest pinned releases.
- **Partial upgrades:** impossible inside one release. Trying an upgrade on a few templates means
  releasing the whole catalog and deploying it only to a test environment, or splitting the catalog.
- **Cost:** built.

### B. One pin per reference

Every reference into another catalog — each stencil node, each theme binding, each font or image
reference — carries its own release of the target catalog.

- **What a release means:** no single answer. "Which B does `letters@1.1.0` use?" becomes a list.
- **Upgrading:** one decision per reference. A catalog with 100 templates and a shared letterhead
  inserted in all of them has 100 pins to move. This is the per-instance busywork the release model
  set out to remove for stencils (#1032, #1047), returning one level up.
- **Consistency within a document:** **not guaranteed.** Two references in one template can point at
  different releases of B, which is exactly the mixed-document failure above.
- **Retention:** the most pinned releases.
- **Partial upgrades:** at any granularity, including within one template.
- **Cost:** the most. Every reference site (`ResourceReferenceSites` enumerates them) gains a pin, the
  editor's pickers gain a version, and every upgrade screen is per reference.

### C. One pin per catalog, overridable per template

Option A as the default, plus an explicit override: **this template uses B at a different release**.
The override applies to everything the template reaches in B — its theme, its stencils, their fonts —
so the template is consistent within itself. A template without an override uses the catalog pin.

- **What a release means:** one combination plus named exceptions. `letters@1.1.0` is "`B@2.0.0`,
  except `invoice` and `reminder`, which use `B@1.0.0`". The exceptions are listed on the release, in
  the release dialog, and as quality findings until removed.
- **Upgrading:** still one decision by default. Partial upgrade becomes possible in both directions:
  _most move, a few wait_ (pin the few back), and _a few move, most wait_ (keep the catalog pin, and
  override the few forward).
- **Consistency within a document:** guaranteed, because the override is per template and covers the
  whole template.
- **Shared resources of the consuming catalog** need a rule. A stencil or theme of `letters` that
  references B is used by templates with different pins. It resolves B **through the template being
  rendered**, so the same `letters` stencil renders against `B@1` in `invoice` and `B@2` elsewhere. It
  is correct, but a subtle thing to explain, and the #1047 interface check has to run per template
  for such a stencil.
- **Retention:** as many releases of B as there are distinct pins in live releases; typically two
  during a transition.
- **Deployment:** unchanged. The environment deploys `letters@1.1.0`, which carries its overrides.
- **Cost:** moderate. The pin table gains a template-level row, resolution checks it before the
  catalog row, the editor and release dialog show and edit overrides, and the bump preview handles
  "these templates are pinned back".

### D. One pin per catalog, and partial upgrades by splitting or copying

Option A, with no new mechanism. A template that must stay on the old dependency is moved to its own
catalog, or the resource it needs is copied into the consuming catalog (or a stencil instance is
detached into plain content).

- **What a release means, upgrading, consistency, retention:** as A.
- **Partial upgrades:** possible, but heavy and permanent-looking for what is usually a temporary
  state. Moving templates between catalogs changes their address — a crude move today. Copying a
  resource forks it and loses its updates for good.
- **Cost:** none to build; the cost is in using it.

### E. Version ranges or modes (orthogonal)

`B@^1.2`, `@latest`, `@working`: how the _declaration_ chooses a release, as the plan's post-major
list describes. Any of A–D still has to decide what is recorded when the release is cut; a range only
changes which release that is. Recorded here so it is not mistaken for an answer to this question.

### Trying an upgrade without any of this

Independently of the option chosen, the common case — _try the new release of B on a few templates
before committing_ — is mostly served by tools that exist or are planned:

- preview the working copy, which already uses B's newest release;
- release `letters`, deploy it **only to a test environment**, and test the templates through real
  generation; production keeps serving the previous release until someone deploys;
- a prerelease (`1.1.0-rc.1`), if "latest" learns to skip prereleases.

What none of these give is **production** serving the new B for some templates and the old B for
the rest. That is the case C exists for.

## Decision

**Open.** The leaning recorded on 2026-10-01 is towards **C — a catalog pin with per-template
overrides** — because a catalog of 100 templates should not have to upgrade a dependency all at once
in production, and because a per-template override keeps every document internally consistent, which
B does not.

Against it, and the reason A was built first: A already covers trying an upgrade (through a test
environment) and keeps "what does this release use" a one-line answer; C's override on shared
resources of the consuming catalog is the subtle part; and C can be added on top of A later without
changing anything A recorded.

If C is adopted, these are proposed with it:

1. **The catalog pin stays the default** and is what a release dialog leads with. An override is an
   exception that is listed, not a second way of working.
2. **An override is per template and covers everything the template reaches in that catalog** —
   never per reference, so B's failure mode cannot occur.
3. **Overrides are recorded at release** like the catalog pin: a row `(release, template, dependency
catalog) → dependency release` beside `release_dependencies`, written in the same transaction.
4. **Rendering resolves template override first, then catalog pin.** A resource of the consuming
   catalog that references the dependency resolves through the template being rendered.
5. **An override is visible until removed:** on the template, in the release dialog, as a quality
   finding ("invoice still uses system@1.3.0"), and in the bump preview.
6. **The compatibility check of #1047 runs per pin**: bumping the catalog pin checks the templates
   that follow it; moving an override checks that template.
7. **No override on resources inside the dependency.** A stencil of B is only ever read as a whole
   release of B; overriding parts of B is option B by another name.

## Consequences

- With A only (today): one combination per release, one decision per dependency upgrade, partial
  upgrades only through a test environment or by splitting a catalog.
- With C: partial upgrades in production, at the cost of a second pin level, an override UI, per-pin
  compatibility checks, and the shared-resource rule in point 4. Retention keeps more releases of a
  dependency alive during a transition.
- Either way: the release dialog should name dependency changes ("system 1.3.0 → 1.4.0, affects these
  templates"), so that adopting a new release of another catalog is a reviewed step rather than a
  side effect of releasing. That is needed under A and C alike.
- Either way: a suite upgrade that ships a new system catalog never changes an existing release.

## References

- [ADR 0026: Revisions, releases and the working copy](0026-revisions-releases-and-the-working-copy.md)
- [Catalog release model v2: the migration plan](../catalog-release-model-v2.md)
- [#1047 — stencils by revision, the per-instance interface check](https://github.com/epistola-app/epistola-suite/issues/1047)
- [#1057 — generate documents from catalog releases](https://github.com/epistola-app/epistola-suite/pull/1057)
