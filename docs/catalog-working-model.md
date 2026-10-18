<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Working on catalogs: the model (2.0)

> **Status:** proposal, 18 October 2026. The screens say _deployed_ where this page says _live_
> (see [workspace-shell.md](workspace-shell.md)). Supersedes the "simple model" section of
> [catalog-workspace-navigation.md](catalog-workspace-navigation.md) where they differ. Needs an
> amendment to contract ADR 0002 (drafts) and a change to how dependencies are locked (ADR 0003).
> **Built since (18 October 2026):** ADR 0002 amendment 2, stage 1. A draft is numbered when it is
> published; the next version is explicit; "side work" is _long-term work_, named and never published
> until it becomes the next version (promotion and pre-releases are stage 2). The fix of what is live
> described below is not built: hotfixes follow ADR 0001, fitted to this model later. Dependencies are
> exact pins checked per use. The current behaviour is in [catalog-versioning.md](catalog-versioning.md).

## The balance

Two needs pull against each other:

- **Control** — what an application uses must be exact, reproducible, auditable and reversible.
- **Usability** — the people doing the work are mostly not technical. They want to change a letter
  and see it, without ceremony.

The model puts the control where it pays off and nowhere else:

> **Work freely. Control what goes out.**
>
> While working, nothing is numbered, nothing is locked, and every catalog sees every other
> catalog's work. Control applies at two moments only: **publishing** (a version is frozen,
> numbered and tied to exact versions of what it uses) and **making live** (an environment starts
> serving that version).

## Words people meet

There are four, and only four:

| Word                  | Is                                                                                                               |
| --------------------- | ---------------------------------------------------------------------------------------------------------------- |
| **Next version**      | The work on a catalog. One per catalog. What every page edits unless it says otherwise. No number.               |
| **Published version** | A frozen copy, numbered (`v1.3.0`). Never changes. What applications get.                                        |
| **Deployed**          | Which published version an environment serves, per catalog. One per environment; deploying another replaces it.  |
| **Side work**         | Other work on the catalog, kept apart from the next version, always with a name and a reason. The advanced case. |

"Draft", "release", "renumber", "base", "behind", "dependency lock" stay out of the default screens.

## Rules

1. **One next version per catalog.** It is an explicit pointer, not "the highest draft". Plain URLs
   (`/templates/letters/invoice`) are the next version.
2. **Numbers exist only on published versions.** Publishing picks the number from the kind of change
   (fix → patch, addition → minor, breaking → major). Side work that fixes a published version is
   published as its next patch. Nobody renumbers anything.
3. **Published versions never change.** Changing one means side work started from it.
4. **Live is per environment, one version per catalog**, with a history; going back is making an
   older version live again.
5. **While working, a catalog follows or stays on each catalog it uses**, chosen per catalog:
   - **Follow Shared's work** (default for authored catalogs in the tenant): Letters previews with
     Shared's next version (already how the editor and preview work), and publishing Letters
     publishes Shared along when it uses unpublished changes from it (rule 7).
   - **Stay on Shared v2.3** (default for subscribed catalogs): Letters previews and publishes with
     v2.3 exactly; a newer one shows as "Shared v2.4 is available: what changed · Use it".
6. **Side work started from a published version sees what that version used.** A fix of what is in
   production previews with the versions of Shared that production uses — otherwise "fix production"
   would preview something production never had. It can deliberately switch to newer.
7. **Publishing ties a version to exact published versions of what it uses**, and **publishes along
   what it needs**: when Letters follows Shared and uses changes in Shared that are not published yet,
   publishing Letters publishes Shared too, in one go — all or nothing. Shared's next version may hold
   unrelated unfinished changes; the dialog lists all of them and offers to stay on the published
   Shared instead, unless Letters uses something that exists only in Shared's work.
8. **Changes published from side work flow back.** After a fix is published, the next version is
   offered to take it over. Per resource: unchanged in the next version → taken over automatically;
   changed in both → the person chooses (keep mine / take the fix / compare).
9. **Nothing is lost silently.** Setting work aside keeps it as side work; discarding is a separate,
   confirmed action.

## The scenarios

### 1. Everyday: change a template and have the application use it

Open the catalog → edit the template (it is the next version) → **Publish…**: the dialog says what
changes and what that means, picks `v1.3.0`, and ticks _Make it live in Test_ → done. Production later:
**Make live** `v1.3.0` in Production.

### 2. Two catalogs that depend on each other (a shared stencil)

Change the footer stencil in **Shared**, and a template in **Letters** that uses it. Both previews show
the new footer at once — no publishing needed to try it. **Publish…** in Letters says:

> Letters uses Footer from Shared, which is not published yet. Publishing Letters publishes Shared
> too, as v2.4.0.

One action; Shared is published first, Letters is tied to it, in one transaction.

### 3. Hotfix: something is wrong in production while the next version is half done

Production serves `v1.2.0`; Test serves `v1.3.0`; the next version has unfinished work. On the
catalog page: **Fix what is live in Production**. This starts side work named _Fix of v1.2.0
(Production)_, from `v1.2.0` exactly, seeing the versions of other catalogs that `v1.2.0` uses (rule 6).
Every page shows a banner while in it. Fix → **Publish…** (`v1.2.1`, ticked: live in Production) → the
side work is done and disappears. The next version shows:

> v1.2.1 fixed Invoice. **Take it over** — Invoice is not changed in the next version, so it is taken
> over as is.

### 4. A change to what is in production that is not urgent

The same as 3: side work from what is live, published as a patch. "Fix" covers any change to a
published version; the name can be changed.

### 5. Stashing: put the work aside

**Set aside…** on the next version: give it a name (_New layout, paused_). It becomes side work; a
new next version starts from the latest published version. Later, on the side work: **Make this the
next version** — the current next version is set aside in turn (rule 9), and the one brought back is
offered what was published since it was set aside (rule 8).

### 6. Two lines of work at once (a big change next to small ones)

**New side work…** from the next version: _2027 redesign_. Small changes go on in the next version
and are published as usual; the redesign is published when ready (its number follows from its changes
and must be above every published one) or made the next version (scenario 5).

## What the screens show

- **Catalog page:** what to do now (next version state + Publish, where it is live + Make live);
  templates; parts; Live; under More: published versions, side work, other catalogs, settings.
- **Side work:** listed under the next version when there is any (_Fix of v1.2.0 (Production) — Continue_);
  a banner on every page while in it.
- **Actions where they are needed:** _Fix what is live in X_ on the Live rows; _Set aside…_ on the next
  version; _Make this the next version_ and _Discard_ on side work; _Take it over_ when something was
  published that the next version lacks.

## What it changes, technically

| Change                                                                                                 | Where                                        | Contract      |
| ------------------------------------------------------------------------------------------------------ | -------------------------------------------- | ------------- |
| A draft has a name and no number until published; addressed by an id in URLs and REST                  | core, UI, REST                               | ADR 0002      |
| The catalog points at its next version explicitly                                                      | core                                         | ADR 0002      |
| Renumbering and the draft-number check go (Publish picks the number)                                   | core, UI, REST                               | ADR 0002      |
| Side work started from a published version resolves other catalogs at that version's locks (rule 6)    | core (`catalog_current_versions`, resolvers) | ADR 0003      |
| Publish several catalogs in one transaction, in dependency order                                       | core                                         | new operation |
| Take over: a resource-level three-way merge (base / published / next version) with choices on conflict | core, UI                                     | new operation |
| One live version per catalog per environment in the UI (the API keeps version selectors)               | UI                                           | none          |

What stays: immutable releases, fingerprints and locks, ready marks (set by publishing), several drafts
in core and REST — the UI just names them and gives each a purpose.

## Decided

- **One live version per catalog per environment** (18 October). Making a version live replaces the
  one that was live, in one transaction, logged as "made v1.1.0 live, replacing v1.0.0". The API keeps
  serving several when asked (`deployRelease` without replace). Built: `DeployRelease(replace = true)`,
  used by every "make live" in the UI.
- **Dependencies are always used implicitly, and can also be deployed explicitly** (18 October). A
  deployed version renders the versions it is pinned to from the tenant's copy, so they are not
  deployed along; the environment says what comes along. Deploying a dependency yourself, or
  `includeDependencies`, deploys it explicitly too (contract ADR 0003).
- **Follow or stay per catalog used** (18 October), rule 5.

## Open questions

1. **Fix from what is live, or from any published version?** Starting from "what is live in X" is what
   people mean; any published version can stay available under More.
2. **Merge granularity.** Per resource (a template as a whole) is simple and predictable; per variant
   or per block would merge more but is harder to explain. Proposal: per resource first.
3. **Who may make live in production?** A separate permission exists (`ENVIRONMENT_DEPLOY`); an
   approval step is not proposed here.
