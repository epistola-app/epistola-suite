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

The context bar spans the top and the footer spans the bottom; the side nav and the page sit between
them, so the nav ends where each begins. An installation notice (maintenance, demo warning) belongs
to the page column below the bar, not to the shell's chrome, so it never shifts the nav or the footer.

On phones the bar is two rows: navigation and actions above, the context breadcrumb below. Platform
pages (the tenant list, site banner, namespaces, sharing) use the same bar and footer with a
`Platform` crumb instead of a workspace, and no side nav. The footer is product chrome only —
version, docs, changelog and installation status; identity and account actions, including Log out,
live in the bar's account menu.

## Two places

| Where                | Context bar                                            | Side nav                                                                                                   |
| -------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| **Inside a catalog** | `Acme / Letters ▾ / Next release ▾`, changes, Publish… | Overview, Quality · Templates, Stencils … Code lists · Releases, Deployments, Dependencies, Settings       |
| **Outside one**      | `Acme / Environments`, "← Back to Letters"             | Home · the 5 catalogs changed most recently and All catalogs · Documents · Operations · Settings · Support |

A page is inside a catalog when its handler puts a `CatalogDraftBar` in the model as `draftBar`, as
every content page already did for the old catalog bar, or — from a feature module, which cannot
build one — the catalog's segment as `workspaceCatalog` (the quality report does). `ShellModelInterceptor` turns that into a
`Workspace` (`handlers/Workspace.kt`) for every full page of a tenant.

## The context bar

- **Catalog switcher (Letters ▾):** the catalogs people work with, in two groups, _You can change_ and
  _Read-only here_ (ADR 0032). The system catalog, part of every tenant, is left out; every catalog is under _All
  catalogs_. Built as a list, so a filter and a _Starred_ group can be added without changing it.
- **Version (Next release ▾):** the next release nearly always. The menu holds the advanced cases out
  of the way: parallel work, each by its name and what it started from, _Start parallel work…_,
  and _Published versions_. Parallel work turns the bar amber, with a strip under it saying it is
  kept apart from the next release, and a way back; publishing it asks whether everyday work continues from it. Hotfixes of
  what is deployed come later (contract ADR 0001), fitted to the next-version model.
- **Status:** what the version has not published yet and **Publish…**, or, with nothing in progress,
  **Start the next release**. Loaded after the page (`/catalogs/{segment}/bar-status`), because
  working it out builds the catalog.
- **Outside a catalog:** the page's name, and the way back to the catalog someone was last in, kept
  in their session.

## Dependencies

A published version pins exact versions of the catalogs it uses; nobody picks a version range. When
a newer version of one is published, the catalog's Dependencies page and the publish dialog say
whether it **fits what this catalog uses**: Epistola compares the two versions resource by resource
and counts only breaking changes to resources used here (`GetCatalogBorrowing.newerBreaks`). One that
fits is moved to at the next publish; one that does not is listed with what breaks, and the catalog
stays where it is. See contract ADR 0002, amendment 2.

## Moving around

Inside the workspace a link or form is boosted, and replaces only the page (`#ws-page`): the server
answers with `layout/boosted` and `HX-Retarget: #ws-page`. The side nav comes along out of band,
since what it highlights follows the page. The context bar comes along only when it changed: the
page says which bar it shows (`#ws-shell-key`, sent back as `X-Epistola-Shell`, the bar's
`Workspace.barKey`), and the server compares. When it did not change, `HX-Trigger:
ws-status-refresh` refreshes the bar's status in place, so it never empties. The version menu links
to each version's overview, so the bar is the same on every page of a catalog version. From a page
without the workspace (a tenant list, a profile) the whole body is swapped, as before.

## The catalog's pages

| Page                                                               | Shows                                                                                                                                                      | Segment     |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| Overview                                                           | What is not published yet and the action that moves it on; templates; About (name, description, key)                                                       | the version |
| Templates, Stencils, Themes, Images, Fonts, Attributes, Code lists | The catalog's own, then — read-only — what the catalogs it depends on hold of that kind. No catalog filter, and New creates in this catalog without asking | the version |
| Releases (`/releases`; `/versions` redirects there)                | The next release, side work, the published releases, each with the release it started from                                                                 | the catalog |
| Deployments                                                        | Which release each environment serves; deploy another; fix what is deployed                                                                                | the catalog |
| Dependencies                                                       | What it depends on (and Edit dependencies…), and which catalogs depend on it                                                                               | the version |
| Settings                                                           | Name, description and presentation (published with each release); key; Exchange                                                                            | the version |

**Words.** Environments get a catalog version by _deploying_ it: the page is Deployments, the action
Deploy, the state "deployed to production". "Live" is not used. Deploying uses the versions of other catalogs a version is pinned to as they are
(they render from the tenant's copy), so they are never deployed along; the environment page lists
them under "Comes along".

Parallel work's overview is its draft page (`/catalogs/letters@<draft id>/draft`); the next
version's is the catalog page (`/catalogs/letters/browse`).

## The editor

The template editor keeps the screen. Its bar is the context bar on one line: back to the template,
`Letters / Next release / Invoice`, and the same status and Publish… as the full bar. Side work is
marked there too.

## Not built yet

- A filter and favourites in the catalog switcher (not needed below about fifteen catalogs).
- Catalogs installed only because another uses them: there is no such state yet (an unmet dependency
  is refused at install). When installs bring dependencies along, the installation should record it,
  and the switcher should leave those out.
- A template switcher in the editor's bar.
- _Set aside…_ in the version menu (see the working model).
