# ADR 0007: Create-form validation-error handling in modal dialogs

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** Epistola team
- **Tags:** ui, htmx, forms, validation, dialogs, css, architecture

## Context

Every "create new" flow now opens in a shared modal `<dialog>` loaded into `#dialog-host`
over HTMX (see [`docs/htmx.md`](../htmx.md) → "Create Forms: Modal Dialogs"). The migration
to dialogs left **validation-error handling inconsistent** across the eleven create dialogs,
in two independent ways:

- **Delivery** — how the error _text_ gets onto the page:
  - The eight text forms (templates, themes, api-keys, environments, stencils, attributes,
    code-lists, catalogs) **re-render the whole `createForm`** (`hx-target="this"`), so the
    error spans come back with the form.
  - **fonts/assets** returned an **HTTP 400 JSON** body parsed by a bespoke inline
    `htmx:responseError` `<script>` that wrote the message into a single error span — the odd
    one out, and per-form JS.
  - **load-test** posted to a localized `#form-error` region and swapped a `form-error`
    fragment into it.
- **Styling** — how the error _border_ is drawn: each text form toggled a
  `.form-group.error` class server-side via `th:classappend="… 'error'"`; fonts/assets/load-
  test had no per-field border at all.

Hard constraints that shaped the choice:

- **A `<input type="file">` value cannot be repopulated server-side** (browser security), and
  the load-test cascade selections cannot be cheaply rebuilt. So a whole-form re-render
  **destroys in-progress state** for those forms — the reason fonts/assets/load-test diverged
  in the first place. The divergence is principled, not accidental.
- **CSP forbids `eval`** (`script-src` has no `unsafe-eval`; `hx-on::*` is banned), so error
  handling must be server-driven or use inline `addEventListener`, never `hx-on`.
- **`POST /tenants/{id}/assets` is shared.** The editor's `uploadAsset` calls it with a raw
  `fetch` (`Accept: application/json`) and depends on a **JSON 400** on failure. Any change
  must preserve that contract for the non-HTMX caller.
- We wanted **one mental model and one styling mechanism**, not "8 + 3 snowflakes."

### Decision drivers

- **Consistency** — one rule a contributor can apply to any new create form.
- **Preserve un-reconstructable state** — never wipe a chosen file or cascade selection on a
  validation error.
- **CSP-safe, no per-form JS** — prefer server-rendered HTML over bespoke client scripts.
- **Keep the editor's JSON contract** intact.
- **Per-field inline errors** where the form can support them (UX parity with the text forms).

Two orthogonal questions follow.

## Decision 1 — Error delivery: "re-render the largest reconstructable region" (decided)

A single rule subsumes all three patterns: **on a validation error, re-render the largest
region whose state the server can reconstruct.**

- **Text forms** — state round-trips through `formData`, so re-render the **whole
  `createForm`** (unchanged).
- **fonts/assets** — the form holds an un-reconstructable file, so re-render **only the error
  spans** via HTMX **out-of-band** swaps: the handler returns **HTTP 200** with
  `reswap(HxSwap.NONE)` + one `oob(...)` fragment per error target (each fragment's root
  carries `hx-swap-oob="true"` + a stable `id`). Field-keyable problems map to per-field spans
  (`#font-error-slug`, …); face/file problems map to a general region (`#font-error-general`).
  Because the primary swap is skipped, the `<form>` and its file inputs are untouched.
- **load-test** — already a localized `#form-error` region; **unchanged**.

### Considered options (delivery)

- **Option A — graduated region (chosen).** Self-swap / OOB-spans / localized-region per the
  rule above. Honours the intrinsic differences while unifying the _principle_.
- **Option B — one localized `#form-error` region for every form.** Simplest, but throws away
  the per-field inline errors the text forms already have, and the eight forms would regress to
  a single lumped message.
- **Option C — one parameterized dialog/form shell.** Fold all eleven into a single
  `fragments/create-dialog.html` driven by `multipart` / `selfSwap` / `cascade` flags. Rejected:
  the fields are irreducibly per-entity and the flags turn the shell into config soup — it
  trades "uniform but slightly repeated" for "DRY but two patterns" (see
  [`docs/htmx.md`](../htmx.md) → "Why per-entity `new.html`"). Per-entity templates with a
  **shared trigger + footer** (`fragments/dialog.html`) is the standing decision.

## Decision 2 — Error styling: `data-error` + CSS `:has()` (decided)

Error borders come from **one CSS rule** keyed off the error element, not a server-toggled
parent class:

```css
.form-group:has(.form-error[data-error="true"]) input,
.form-group:has(.form-error[data-error="true"]) textarea,
.form-group:has(.form-error[data-error="true"]) select {
  border-color: var(--ep-destructive);
}
.form-error:empty {
  display: none;
}
```

Each error span is **always rendered** and carries
`th:attr="data-error=${errors?.containsKey('<field>')}"`. The `th:classappend="… 'error'"`
toggle is retired everywhere (including the tenant create form and the shared
`fragments/form-fields.html` macros).

This is what makes Decision 1 cohere: the same rule borders **both** a whole-form re-render and
an OOB span swap, because the styling is a pure function of the swapped element's attribute —
the server never toggles a parent class.

### Considered options (styling)

- **Option S1 — `data-error` + `:has()` (chosen).** One rule, no server-side class toggling,
  works identically for self-swap and OOB. Requires `:has()` (Baseline since Firefox 121,
  Dec 2023 — the app already requires native `<dialog>`/`:modal`).
- **Option S2 — keep `.form-group.error` + `th:classappend`.** Familiar, but cannot style an
  OOB-swapped span without also swapping (and resetting) the input, and leaves two mechanisms
  once fonts/assets need borders — exactly the inconsistency this work removes.

## The shared-endpoint corollary

`AssetHandler.upload` branches on `request.isHtmx`: the dialog (HTMX) gets the OOB-200 error
response; the editor (non-HTMX, `Accept: application/json`) keeps the **JSON 400**. fonts keeps
the same JSON-400 fallback for its non-HTMX/test callers. One endpoint, two error contracts,
selected by caller.

## Consequences

**Positive**

- One rule for delivery, one CSS rule for styling — a new create form needs no bespoke error
  JS and no per-form CSS.
- File/cascade state survives a validation error (proven by a Playwright risk-gate test that
  asserts the chosen file is still selected after an OOB error render).
- The bespoke `htmx:responseError` JSON-parsing scripts are deleted from fonts/assets.

**Costs / things to watch**

- **The duplicate-id field map is a fragility.** `executeOrFormError` maps a
  `DuplicateIdException` to a form field via a hardcoded `when (entityType)`; an entity missing
  there keys the error under `"id"` — a field no form renders — and the message **silently
  vanishes**. This bit stencil. Every slug-based create entity must be listed (now: environment,
  template, tenant, theme, attribute, code-list, stencil, catalog). A new entity that throws a
  duplicate and isn't added will regress the same way.
- **OOB error targets need stable `id`s and must be cleared every response.** OOB only swaps
  what's sent, so a fixed error renders by sending its slot back with `data-error="false"`
  (empty) — the handler emits **all** slots on each error response.
- **`:has()` browser baseline** — a hard floor; acceptable given the existing `<dialog>`/`:modal`
  requirement, noted here so it isn't rediscovered.
- **Errors must reach a rendered region.** A failure path that throws _before_ the form-error
  fragment is returned (e.g. parsing user JSON outside a `try`) surfaces as a global 500 behind
  the modal, not in the dialog — every reachable error must route to its region. (This bit
  load-test's invalid-JSON path.)

### Related

- [`docs/htmx.md`](../htmx.md) → "Create-form error handling", "Create Forms: Modal Dialogs",
  "Why per-entity `new.html`".
- [`docs/data-testid-reference.md`](../data-testid-reference.md) — error-span `id` scheme.
- `modules/epistola-web/.../htmx/FormBinder.kt` (`executeOrFormError`, the entityType→field map),
  `apps/epistola/.../handlers/FontHandler.kt` / `AssetHandler.kt` (OOB error responses),
  `apps/epistola/.../static/css/main.css` (the `:has()` rule).
- [ADR 0004](0004-rfc7807-problem-details.md) — the REST API error model (a separate surface;
  these UI handlers are not REST and deliberately render HTML, not RFC 9457).
