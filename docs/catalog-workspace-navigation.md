<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Catalog workspace navigation (2.0)

> **Status:** design proposal, 17 October 2026 — not built, for review before implementation.

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
- The catalog context strip (`catalog-context`, alpha) is off by default; the 2.0 design said it
  would become the default chrome (WP7) and it has not.
- Releases cannot be browsed as content at all, only as a list of versions.
- Tenant-wide lists ("all templates") are ambiguous once a catalog has several versions: the same
  template exists in several, with different content.

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

Open: where does someone land after logging in, or when they open "Authoring"? Options:

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

- **Landing** (above).
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
