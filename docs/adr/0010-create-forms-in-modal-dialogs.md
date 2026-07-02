# ADR 0010: Create forms open in modal dialogs, not pages

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Epistola team
- **Tags:** ui, htmx, forms, dialogs, architecture

## Context

Every "create new" flow used to be a standalone **page**: the list had a "New" link that
navigated to `…/new`, the server rendered a whole page (shell + nav + form), the form POSTed, and a
success redirected back to the list — a second full-page load. Starting and finishing a create cost
two whole-page renders and threw away the list's context (filters, scroll) both times.

We migrated every create flow (templates, themes, api-keys, environments, stencils, attributes,
code-lists, catalogs, fonts, assets, load-tests) to a **modal `<dialog>`** loaded over HTMX into a
single shared `#dialog-host`, while the list stays rendered underneath. This was a deliberate,
breaking UI change (`refactor(ui)!`). This ADR records that decision and its fallout; the
[visual guide](../create-form-dialogs.md) has the diagrams and the full pros/cons.

### Decision drivers

- **Keep list context.** Creating shouldn't navigate away from the list and lose filters/scroll.
- **No full-page reloads** to open the form or after a successful create.
- **Stay addressable.** An open create form should still be bookmarkable, refresh-safe, and
  back-closable — the one real thing a page URL gave us.
- **Server-rendered forms.** The forms need server data (catalog dropdowns, the load-test cascade,
  permission-gated fields, CSRF), so the markup must come from a handler, not be purely static.
- **One uniform pattern** a contributor can apply to every create entity.

## Decision

Every create flow opens a shared modal `<dialog>`, fetched over HTMX from the entity's `…/new`
endpoint into the single `#dialog-host` in `layout/shell`. The supporting decisions:

1. **Lazy server-fetch on open.** Clicking "New" does `hx-get …/new`, which returns the
   `createDialog` fragment (rendered with its data); HTMX injects it into `#dialog-host`. The form
   and its data are fetched only when the dialog is opened.
2. **Per-entity `new.html`** with `createDialog` / `createForm` / `fields` fragments. Only the two
   pieces with zero per-entity variation — the list **trigger** and the **footer** — are shared
   (`fragments/dialog.html`).
3. **Dialog-only — no full-page form.** A direct (non-HTMX) `GET …/new` has no dialog host to inject
   into, so it **redirects to the list** rather than rendering a chromeless fragment or a second
   render path.
4. **Deep-linkable.** Opening pushes a query param (`?create`, or `?upload` for file forms) onto the
   list URL; an entity-agnostic reconcile script in `fragments/htmx` keeps the dialog's open/closed
   state in sync with the URL on load, refresh, and back/forward. On `?create` the list renders the
   dialog inline so the deep link opens it.
5. **Trigger is an `<a>` with `hx-boost="false"`.** The trigger carries both `href` (no-JS fallback)
   and `hx-get` into `#dialog-host`. `hx-boost="false"` is required because `<body hx-boost="true">`
   would otherwise route the click to the handler's non-HTMX branch (boosted requests are treated as
   navigation) and swap into the boost target instead of `#dialog-host`. The footer Cancel is a
   no-request `<button data-dialog-close>` for the same reason.

### Considered options

- **Option A — lazy-fetched modal dialog, per-entity templates, dialog-only (chosen).** Keeps list
  context, no reloads, one uniform pattern, and the form's server data is fetched only on open.
- **Option B — keep standalone pages.** Simplest mental model, fully addressable, no-JS friendly —
  but two full-page reloads per create and the list context is lost. Rejected.
- **Option C — one parameterized shared dialog shell** (`fragments/create-dialog.html` driven by
  flags). Rejected: the fields are irreducibly per-entity and the flags turn the shell into config
  soup (see [`docs/htmx.md`](../htmx.md) → "Why per-entity `new.html`").
- **Option D — always-inline the dialog in `list.html`, open client-side, no `/new`.** Removes the
  `/new` endpoint, the redirect, the anchor, and `hx-boost` juggling in one move — genuinely simpler
  for trivial forms. Rejected as the global pattern because it would render the form and run its data
  queries on **every** list view, and **load-test's cascade needs a server GET endpoint regardless**,
  so it couldn't be fully client-side anyway. Inlining also wouldn't remove the server-rendered form
  fragment (the validation-error re-render needs it) — it would only relocate it into `list.html`.

## Consequences

**Positive**

- No full-page reloads — one fragment swap to open, an `HX-Redirect` (or in-dialog reveal) on
  success. The list stays put with its filters and scroll.
- Validation errors render inside the open dialog without losing the form or input (the error model
  is [ADR 0011](0011-create-form-validation-errors.md)).
- Still addressable: `?create` / `?upload` make the open dialog deep-linkable, refresh-safe, and
  back-closable.
- One shared `#dialog-host` + the open/close/reconcile script serve every entity; per-entity
  templates stay simple (three fragments, no routing of their own).

**Costs / things to watch**

- **More moving parts** than a page + POST: HTMX swap targets, the reconcile script, and CSP
  constraints (no `hx-on::*` — use inline `addEventListener`).
- **Requires the error convention.** Because the form lives in a modal, errors need the per-field
  span + shared `#dialog-error` card discipline of [ADR 0011](0011-create-form-validation-errors.md),
  which is build-enforced.
- **No standalone form page.** A direct `GET …/new` redirects to the list — there is no bookmarkable
  blank-form page and no no-JS form fallback (the deep link opens the list, then the dialog, via
  HTMX). The `/new` endpoint, its redirect, the trigger anchor, and `hx-boost="false"` are all
  consequences of the lazy-fetch choice and stand or fall together.
- **File / cascade forms can't self-swap** (a file input can't be repopulated; the load-test cascade
  can't be cheaply rebuilt), so they use out-of-band per-field error handling — extra complexity the
  simple text forms avoid.
- **Focus / a11y leans on native `<dialog>`** (`showModal()` / `:modal`); sub-region swaps inside a
  dialog (the load-test cascade) need `hx-disinherit` so descendants don't inherit the form's swap
  target.

### Related

- [ADR 0011](0011-create-form-validation-errors.md) — how errors render inside these dialogs.
- [`docs/create-form-dialogs.md`](../create-form-dialogs.md) — visual guide (diagrams + pros/cons).
- [`docs/htmx.md`](../htmx.md) → "Create Forms: Modal Dialogs", "Why per-entity `new.html`",
  "Deep-linkable create dialogs".
- The `htmx-form` skill — the template/handler/routes recipe for a new create flow.
