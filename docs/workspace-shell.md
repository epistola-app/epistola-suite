<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# The workspace shell (2.0)

> **Status:** proposed, 18 October 2026, and built on the 2.0 branch (unreleased). Replaces the top
> menus (Authoring, Resources, Operations, Settings) and the catalog bar above content pages. The
> working model it serves is [catalog-working-model.md](catalog-working-model.md).

The people using Epistola build templates, stencils and themes, and spend most of their time inside
one catalog. The shell keeps that context in view on every page, so nobody has to remember which
catalog, or which version of it, they are changing.

## Two places

| Where                | Context bar                                            | Side nav                                                                            |
| -------------------- | ------------------------------------------------------ | ----------------------------------------------------------------------------------- |
| **Inside a catalog** | `Acme / Letters ▾ / Next version ▾`, changes, Publish… | Overview, Templates · Stencils … Code lists · Versions, Live, Uses, Details         |
| **Outside one**      | `Acme / Environments`, "← Back to Letters"             | Home · your catalogs and All catalogs · Documents · Operations · Settings · Support |

A page is inside a catalog when its handler puts a `CatalogDraftBar` in the model as `draftBar`, as
every content page already did for the old catalog bar. `ShellModelInterceptor` turns that into a
`Workspace` (`handlers/Workspace.kt`) for every full page of a tenant.

## The context bar

- **Catalog switcher (Letters ▾):** the catalogs people work with, in two groups, _Yours_ and
  _Subscribed_. The system catalog, part of every tenant, is left out; every catalog is under _All
  catalogs_. Built as a list, so a filter and a _Starred_ group can be added without changing it.
- **Version (Next version ▾):** the next version nearly always. The menu holds the advanced cases out
  of the way: other drafts (side work), _Fix what is live in …_ per environment that serves a
  version that can be fixed, and _Published versions_. Side work is named by what it fixes
  ("Fix of v1.2.0 · production") and turns the bar amber, with a strip under it saying the next
  version is not affected and a way back to it.
- **Status:** what the version has not published yet and **Publish…**, or, with nothing in progress,
  **Start the next version**. Loaded after the page (`/catalogs/{segment}/bar-status`), because
  working it out builds the catalog.
- **Outside a catalog:** the page's name, and the way back to the catalog someone was last in, kept
  in their session.

## The catalog's pages

| Page                                                               | Shows                                                                           | Segment     |
| ------------------------------------------------------------------ | ------------------------------------------------------------------------------- | ----------- |
| Overview                                                           | What is not published yet and the action that moves it on; templates            | the version |
| Templates, Stencils, Themes, Images, Fonts, Attributes, Code lists | The lists, filtered to the catalog                                              | the version |
| Versions                                                           | The next version, side work, the published versions                             | the catalog |
| Live                                                               | What each environment serves; make another live; fix what is live               | the catalog |
| Uses                                                               | The catalogs it uses, and how it stands with each                               | the version |
| Details                                                            | Name, description and presentation (published with each version); key; Exchange | the version |

Side work's overview is its draft page (`/catalogs/letters@1.2.1/draft`); the next version's is the
catalog page (`/catalogs/letters/browse`).

## The editor

The template editor keeps the screen. Its bar is the context bar on one line: back to the template,
`Letters / Next version / Invoice`, and the same status and Publish… as the full bar. Side work is
marked there too.

## Not built yet

- A filter and favourites in the catalog switcher (not needed below about fifteen catalogs).
- Catalogs installed only because another uses them: there is no such state yet (an unmet dependency
  is refused at install). When installs bring dependencies along, the installation should record it,
  and the switcher should leave those out.
- A template switcher in the editor's bar.
- _Set aside…_ in the version menu (see the working model).
