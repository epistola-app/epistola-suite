<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Catalog workspace navigation (2.0)

> **Status:** design record, 17 October 2026 — superseded by [workspace-shell.md](workspace-shell.md)
> and [catalog-working-model.md](catalog-working-model.md). Since 18 October drafts are numbered when
> they are published (contract ADR 0002, amendment 2): `letters@<number>` addresses, renumbering and the
> in-between version described below are gone.

## Problem

In 2.0 a catalog consists of versions (contract ADR 0002): drafts, which are edited, and releases,
which are immutable and are what environments serve. What a page changes depends entirely on which
catalog and which version it is in. The UI does not show that:

- The navigation is by resource type across catalogs (Authoring: Templates, Themes, Stencils,
  Catalogs; Resources: Images, Fonts, Attributes, Code lists), a shape from 1.x where a resource had
  versions of its own and a catalog was a folder.
- The version a page works in is only in its URL (`/tenants/t/templates/letters@2.0.0/invoice`). A
  bar appears only when a catalog has several drafts or none, so with one draft nothing says "you are
  editing draft 1.0.1 of letters".
- The catalog context strip (`catalog-context`, alpha) was off by default; the 2.0 design said it
  would become the default chrome (WP7). It has since been retired in favour of the version bar.
- Releases cannot be browsed as content at all, only as a list of versions.
- Tenant-wide lists ("all templates") are ambiguous once a catalog has several versions: the same
  template exists in several, with different content.

## Simple model (decided 17 October)

> Superseded where they differ by [the working model](catalog-working-model.md) (18 October).

The people using this are mostly not technical. What they need: change a letter, publish it, make it
live, and now and then fix what is live. So the UI shows only:

- **Your work** — one working copy per catalog. You just edit; no drafts to choose, no numbers.
- **Publish** — shows what changed and what it means in plain words, and picks the number from the
  kind of change (fixes → patch, additions → minor, breaking → major); the number can be changed
  under "advanced". Ready marks and dependency locks are done for you.
- **Live** — per environment, which version is live, and "Make vX live".
- **History** — published versions, viewable; "Fix this version" for one that is live.
- **A fix** — a banner-marked mode on a published version, published as its next patch.

Core and contract stay as they are for now (several drafts remain possible through REST/MCP); the UI
treats the highest draft as your work and any other as a fix. Enforcing that is a later decision.

## Principle

**You work in a catalog version, like a branch.** Every page that shows or edits catalog content is
in exactly one _context_ — a catalog and one of its versions — and says so. Releases are versions
like drafts, browsable in the same navigation, read-only.

Things that are not catalog content (environments, generation history, API keys, settings,
operations) keep their tenant-level pages.

## The context

A context is `(catalog, version)`:

- an authored catalog's **draft** — editable;
- an authored catalog's **release** — read-only: view, preview, export, deploy, open a draft from it;
- a subscribed catalog's **installed release** — read-only.

### Context bar

Always visible on catalog pages, directly under the top navigation:

```
[letters ▾]  [Draft 2.0.0 ▾]  based on 1.4.0 · behind 1.4.1 (1 change)        [Changes 3] [Release…]
```

- **Catalog picker**: the tenant's catalogs, authored first, with type and current version; typing
  filters. Switching catalog lands on the same page type in that catalog's default version
  (e.g. its templates list), never on a resource it may not have.
- **Version picker**: drafts (newest first), then releases (newest first, with the environments that
  serve them), then "New draft…". A release is marked read-only.
- **Status**: the draft's base, the releases it is behind and the change count; for a release, when
  it was released and where it is deployed.
- **Actions**, each gated on its own permission as today: Changes (the review page), Release…,
  Renumber, Discard (drafts); Open draft from this, Deploy, Export (releases).

A read-only context shows the same pages with editing controls withheld and one sentence saying why
("This is release 1.4.0; releases do not change. Open a draft from it to make changes.").

### Navigation inside a context

Replaces the Authoring and catalog-content part of Resources:

```
Templates · Stencils · Themes · Images · Fonts · Attributes · Code lists · Dependencies · Changes
```

Each list shows that version's resources only. The catalog page's current sections move into the
context: _Next release_ becomes **Changes** (what changed since the base, readiness, mark ready,
release), dependencies get their own page, the catalog's metadata and publication stay on a
**Catalog** settings page reached from the catalog picker.

### Built so far (17 October)

The model people see, decided 17 October (see "Simple model" below): one working copy per catalog,
**your work**, and at times a **fix** of a published version.

- **One short bar** above every catalog content page: `Catalog Letters · Your work · [Publish…]`.
  A fix is a banner instead: "You are fixing Letters v1.4.0, which is live in production. Changes
  here go only into this fix. [Publish fix…] [Back to your work]". A subscribed catalog: `v2.0.0 ·
  can't be edited`. No version numbers for your work. `CatalogDraftBar`, `catalogs/drafts.html ::
  content-draft-bar`.
- **URLs:** a plain segment (`/themes/letters/house`) is your work; a fix or a published version is
  named by its number (`letters@1.4.1`). An explicit-version redirect was built and dropped the same
  day: with one working copy a plain URL is not ambiguous.
- The `catalog-context` toggle and `GetCatalogContext` are gone.

### The catalog page, redone (17 October)

People work in the **next version** 99% of the time: based on the latest published version, numbered
only when it is published. The advanced cases are explicit, and every version has its own page.

- **Catalog page** (`/catalogs/{c}/browse`): "From here to a document" until something is live (add a
  template, publish, make live, use it from an application); **Next version** (what is not published
  yet, Open, Publish…, and "Start an in-between version" for something that has to go out first);
  **Versions** (every draft and published version, each linking to its page); **Live**; the catalog's
  identity; Exchange.
- **A draft's page** (`/catalogs/{c}/draft`, or `{c}@{v}/draft`): what is not published yet, its
  contents and its details.
- **A published version's page** (`/catalogs/{c}/releases/{v}`): what it went out with, where it is
  live, export, "Start a draft from this version".
- **Catalog details belong to a version.** A release freezes its own; a draft's are still the
  catalog's, shared by every open draft — to move into the version in core.

The use cases this is checked against: a new catalog to a first document an application makes; keep
improving the next version; an in-between release while the next version is unfinished.

### Routes

The context is in the path, so links, tabs and back buttons keep it, and the server never guesses:

```
/tenants/{t}/catalogs/{catalog}/versions/{version}/templates
/tenants/{t}/catalogs/{catalog}/versions/{version}/templates/{template}
/tenants/{t}/catalogs/{catalog}/versions/{version}/changes
/tenants/{t}/catalogs/{catalog}/versions/{version}/dependencies
```

- `{version}` may be `current` (an authored catalog's highest draft, a subscribed catalog's installed
  release) so a link to "where I work" survives a release renumbering the draft.
- This mirrors the REST addresses (`…/catalogs/{registry}/{namespace}/{key}/versions/{v}/…`); the
  UI keeps the key-only catalog segment it uses today.
- Today's `letters@2.0.0` segment and the tenant-level resource routes redirect to the new form, so
  existing links keep working.
- Every handler passes the context's `CatalogVersionRef` to every message, as the `@version` segment
  does today; a release context reads release content (`GetReleasedResource`,
  `ListReleasedResources`, the release render path), which the core already provides.

## Finding things across catalogs

**Deferred (17 October):** search is left for later; the tenant-wide lists stay until it exists.

Working is per context; finding is not. The tenant-wide resource lists go, because "all templates"
has no single answer once versions differ. In their place:

- **Search** (top bar, every page): one box over every catalog's resources. Each result is a resource
  _in a context_: `invoice — letters · draft 2.0.0`. By default each catalog contributes the version
  a person most likely means (see the open question below); a toggle widens the search to every
  version, grouping one resource's versions under it (`invoice — letters: draft 2.0.0, 1.4.1,
  1.4.0`). Opening a result enters that context.
- **Usages** ("where is this stencil, theme, font, code list used?") stay tenant-wide, because a
  change can break another catalog that depends on this one. Each usage names its context, and by
  default reads each catalog's current version and the releases environments serve — the versions a
  change can still affect.
- The resource graph keeps its tenant-wide view, labelled by context.

## Landing

**Decided (17 October): the tenant home is the catalog overview** — option 2 below. Built first:
`GetCatalogOverview`, rendered on the tenant home page, every draft and release linking into that
version. Whether to jump straight to the last context as well (option 3) is left for later.

The options considered:

1. **The last context they worked in**, remembered per user (and per tenant). Fast for daily use;
   can surprise someone who forgot they were in a hotfix draft — mitigated by the context bar being
   prominent.
2. **A catalog overview**: every catalog as a card with its current version, open drafts, pending
   changes and what each environment serves. One click into a context. Clear, one step slower, and
   a natural home for the "what is going on in this tenant" question.
3. **The overview the first time, the last context afterwards**, with the overview always one click
   away from the catalog picker.

A tenant with exactly one authored catalog (the common case today: `default`) could skip the choice
either way.

## Open questions

- **Search default**: per catalog, its current version only, or its current version plus every
  release an environment serves? The second finds "what is live" without the version toggle.
- **Dependencies' content**: a template in `letters` uses a theme from `shared`. Opening that theme
  from the template should open the release of `shared` that `letters` is locked to (read-only),
  not `shared`'s current draft. That follows the model (ADR 0003) but may surprise authors who
  expect to edit it; the page would offer "Open in shared's draft".
- **Several tenants**: the tenant switcher stays where it is; a context is always within one tenant.

## What exists and what is new

Exists in core and REST: version-scoped messages (`CatalogVersionRef`), release reads, change lists,
behind, the draft lifecycle, the draft-number check. Exists in the UI but moves: the draft bar
(`CatalogDraftBar`), next release (`CatalogReleaseHandler`, `next-release.html`), the catalog
segment. New: the context bar and its pickers, context routes and redirects, read-only rendering of
every resource page for a release, the search page and its query (cross-catalog, version-aware), the
landing page, and the removal of the tenant-level resource lists from the navigation.

## Slices, once agreed

1. Context routes and the context bar on the existing pages, with redirects; release contexts
   read-only. The catalog-context toggle retires.
2. Navigation inside a context; Changes and Dependencies pages; the catalog page slimmed to settings.
3. Search and usages across catalogs, version-aware; tenant-level resource lists removed.
4. Landing, per the decision above.
