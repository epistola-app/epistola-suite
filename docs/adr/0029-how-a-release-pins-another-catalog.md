<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# ADR 0029: How a release pins another catalog

- **Status:** Proposed — no pin by default, template pins as exceptions, upgrade tool first
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
2. **A large catalog moving to a new dependency release in steps.** A catalog with 100 templates
   moves five of them to `B@2.0.0` while the other 95 stay on `B@1.0.0` — not only to try the new
   release, but **released and deployed to production in that state**, for as long as moving the
   rest takes. One pin per catalog cannot express that inside one release of the catalog.

The question this ADR records: **at what granularity does a release pin another catalog — the
catalog, the template, or each reference?** It is a question about the pin, not about where
dependencies come from; those always come from resource references.

### What is wanted

Two things, in order of importance:

1. **A great upgrade tool.** Taking a new release of another catalog — or a changed stencil in one's
   own — must be guided: what changed, which templates it affects, what each looks like before and
   after, what breaks and how to fix it. This is needed whatever granularity the pin has; without it,
   every option below is a blind bump.
2. **Partial adoption, strongly wanted but not required.** A release of a catalog — including one
   deployed to production — that has adopted a new release of another catalog for some of its
   templates and not yet for others, for as long as moving the rest takes. Very useful for a large
   catalog; not a precondition for 2.0.

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

### Against the requirement

| Option | Partial adoption (wanted) | Every document consistent | Upgrade effort by default     |
| ------ | ------------------------- | ------------------------- | ----------------------------- |
| A      | no                        | yes                       | one decision                  |
| B      | yes                       | **no**                    | one decision per reference    |
| C      | yes                       | yes                       | one decision, plus exceptions |
| D      | only by moving or copying | yes                       | one decision                  |
| E      | not this question         | —                         | —                             |

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

**Resources pin nothing by default; a template may pin explicitly. The upgrade tool comes first.**
Proposed; to be accepted by the team.

1. **No pin by default.** No resource — template, stencil, theme — carries a pin of its own. Each
   release of a catalog follows the latest release of every catalog it uses **at the moment it is
   released**, and records which one it was (`release_dependencies`, as #1057 built), so that release
   keeps rendering the same way afterwards. The author never chooses a version; the system records
   one. This is option A, seen from the author's side.
2. **An explicit pin is an exception, on a template.** A template may say "use B at this release"
   (option C). Nothing else may: a document starts at a template, and everything it reaches —
   including the consuming catalog's own stencils and themes — follows that template's pin. A pin on a
   shared stencil or theme could disagree with the template using it and mix two releases of B in one
   document, which every other rule here exists to prevent.
3. **The upgrade tool is the deliverable that matters most**, described below. It works with
   point 1 alone; template pins plug into its "apply in steps" when they are built.

Why not the alternatives:

- **B, a pin on every reference**, makes pinning the default and gives up consistency within a
  document; it is the opposite of point 1.
- **D, splitting or copying**, stays available, but expresses a usually temporary state by moving or
  forking content.
- **Following another catalog live at render time** — no recorded pin at all — would let a release
  or deployment of B change what an existing release of A produces in production, unreviewed, and
  would make rolling A back meaningless. It is not the default. If a catalog that promises
  compatibility (a corporate brand) ever needs it, it can be an explicit opt-in per dependency.

Partial adoption is strongly wanted, not required, so template pins can follow the upgrade tool
rather than precede it. When they are built, these rules come with them:

1. **A pin is per template and covers everything the template reaches in that catalog** — never per
   reference.
2. **Pins are recorded at release** beside the catalog's recorded dependency: a row `(release,
template, dependency catalog) → dependency release`, written in the same transaction.
3. **Rendering resolves the template's pin first, then the release's recorded dependency.** A
   resource of the consuming catalog that references the dependency resolves through the template
   being rendered.
4. **A pin is visible until removed:** on the template, in the release dialog, as a quality finding
   ("invoice still uses system@1.3.0"), and in the upgrade tool's progress.
5. **The compatibility check of #1047 runs per pin**: moving the default checks the templates that
   follow it; moving a pin checks that template.
6. **No pin on resources inside the other catalog.** A stencil of B is only ever read as part of one
   release of B.
7. **A pin can point either way**: holding a few templates back while the rest follow, or moving a
   few forward ahead of the rest, are the same mechanism.

## How a dependency arises, and goes

Implicitly. Nobody declares that catalog A depends on catalog B.

- **Adding.** Using a resource of B in A — a theme on a template, a stencil inserted, a font named, an
  image placed — is what makes A depend on B. The pickers offer B's resources **as B's latest release
  holds them**, not B's working copy: the next release of A pins a release of B, so a resource only in
  B's working copy is one A could not release with.
- **Releasing.** The dependencies are derived from the references when A is released, and each is
  recorded at that catalog's latest release (as #1057 does). The release is refused, naming the
  resource, when a reference does not exist in the release it would be recorded against: "theme
  `brand` is not in shared@2.0.0; release shared first". _(Not built yet: today such a reference is
  only caught when rendering fails.)_
- **Removing.** When the last reference into B is gone, the next release of A does not depend on B.
  Nothing to clean up.
- **Seeing.** A catalog shows what it uses — "system 1.4.0 (12 templates), shared 2.0.0 (3
  templates)" — with its template pins listed beneath.
- **The working copy.** Editing and previewing A's working copy should resolve resources of B in B's
  latest release, so the preview shows what A's next release will render. _(Not built yet: the
  working copy reads B's working copy live.)_

## Pickers

Selecting a theme, stencil, font or image becomes a choice across catalogs, and the picker orders it
so the common case stays one click:

1. **This catalog's own resources** first.
2. **Resources of catalogs it already uses**, grouped by catalog and labelled with the release they
   come from ("shared · 2.0.0"). Choosing one adds no new dependency.
3. **Resources of catalogs it does not use yet**, last and marked as such ("adds a dependency on
   `branding`"). Choosing one is how a dependency arises. A catalog with no release offers nothing
   here, with the reason: there is nothing it could be released against.

"Already uses" means: referenced by this catalog's working copy, which is also what its next release
will record.

**Pinning from the picker.** As stencil versions could be pinned at insertion before, the picker
offers the release to use — the default, which follows the latest, or a specific earlier release.
Choosing a specific one sets **the template's pin for that catalog**, not a pin on the one reference
(point 1 of the template pin rules above): every resource of that catalog the template reaches then
comes from that release. The picker says so when the template already uses other resources of that
catalog ("pins all 4 resources from `shared` in this template to 1.0.0"), and offers only releases
that still contain everything the template uses from that catalog. A resource of the catalog's own
release is never pinned; within a catalog there is one revision of everything (#1047).

## The upgrade tool

One tool for every kind of upstream change a template can be exposed to: a newer release of another
catalog it uses, and a changed stencil or theme in its own catalog (#1047). It runs in the working
copy; what it produces is reviewed again in the release dialog and reaches an environment only when a
release is deployed.

1. **Discovery.** A catalog says when a newer release of a catalog it uses is available, and how
   many of its templates use it ("system 1.4.0 is available; 37 templates use it"). Never an
   automatic change.
2. **Impact, per template.** Every template that reaches the changed resources is classified:
   - _unaffected_ — it uses nothing that changed;
   - _changed, compatible_ — content changed (a theme colour, a stencil's wording) but nothing it
     relies on was removed; it moves along, and its output differs;
   - _breaking_ — something it relies on changed: a filled placeholder removed, a required parameter
     added, a style preset it names gone, a resource it uses removed. Classified per instance, by the
     interface digests of #1047 and the data-contract compatibility rules.
3. **Before and after, side by side.** Each affected template renders from its examples with the
   release it uses now and with the newer one. The renders come from releases, so they are exactly
   what would be generated.
4. **Guided fixes for what breaks.** Re-home a removed placeholder's fill, map a removed preset to a
   new one, bind a new required parameter, replace a removed resource — each an ordinary, undoable
   edit, as #1047 describes for the editor.
5. **Apply in steps.** Take everything unaffected and compatible at once; work through the breaking
   ones template by template. Without template pins, the next release adopts the newer release for
   all templates and waits until every breaking one is fixed. With them, "these templates now, the
   rest later" pins the rest back and the catalog can be released partly moved.
6. **Progress.** A partly moved catalog shows how far it is ("63 of 100 templates on B@2.0.0") and
   which templates are left.
7. **Three surfaces.** The UI first; MCP tools to preview the impact and apply fixes, so an assistant
   can work through a large upgrade; REST to read the impact.

## Consequences

- Authors never manage versions of other catalogs in the normal case: a release follows the latest
  and records it.
- The upgrade tool is built first and works with that default as it is; partial adoption plugs into
  its "apply in steps" once template pins exist.
- With template pins, a released catalog can carry a dependency partly adopted for as long as moving
  the rest takes, in every environment including production.
- A second pin level: a table beside `release_dependencies`, a resolution step before the catalog
  pin, an override UI on templates, per-pin compatibility checks, and the shared-resource rule in
  point 4.
- Retention keeps more releases of a dependency alive during a transition: typically two, one per
  distinct pin in a live release.
- "What does this release use?" becomes the catalog pin plus a listed set of exceptions.
- Until C is built, #1057's catalog pin applies, and partial adoption needs a test environment or a
  split catalog.
- Either way: the release dialog should name dependency changes ("system 1.3.0 → 1.4.0, affects these
  templates"), so that adopting a new release of another catalog is a reviewed step rather than a
  side effect of releasing. That is needed under A and C alike.
- Either way: a suite upgrade that ships a new system catalog never changes an existing release.

## References

- [ADR 0026: Revisions, releases and the working copy](0026-revisions-releases-and-the-working-copy.md)
- [Catalog release model v2: the migration plan](../catalog-release-model-v2.md)
- [#1047 — stencils by revision, the per-instance interface check](https://github.com/epistola-app/epistola-suite/issues/1047)
- [#1057 — generate documents from catalog releases](https://github.com/epistola-app/epistola-suite/pull/1057)
