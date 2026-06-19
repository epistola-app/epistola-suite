# ADR 0008: Create-form error handling in modal dialogs

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Epistola team
- **Tags:** ui, htmx, forms, validation, dialogs, css, architecture

## Context

Every "create new" flow now opens in a shared modal `<dialog>` loaded into `#dialog-host`
over HTMX (see [`docs/htmx.md`](../htmx.md) → "Create Forms: Modal Dialogs"). The migration
to dialogs left **error handling inconsistent** across the create dialogs, in two
independent ways:

- **Delivery** — how the error _text_ reaches the page:
  - The eight text forms (templates, themes, api-keys, environments, stencils, attributes,
    code-lists, catalogs) **re-render the whole `createForm`** (`hx-target="this"`), so the
    error spans come back with the form.
  - **fonts/assets** returned an **HTTP 400 JSON** body parsed by a bespoke inline
    `htmx:responseError` `<script>` that wrote the message into a single error span.
  - **load-test** threw every error into a localized `#form-error` region.
- **Styling** — how the error _border_ is drawn: each text form toggled a
  `.form-group.error` class server-side via `th:classappend`; fonts/assets/load-test had no
  per-field border at all.

Hard constraints that shaped the choice:

- **A `<input type="file">` value cannot be repopulated server-side** (browser security), and
  the load-test cascade selections (template → variant → version/environment) plus the typed
  JSON cannot be cheaply rebuilt. So a whole-form re-render **destroys in-progress state** for
  those forms — the reason fonts/assets/load-test diverged. The divergence is principled.
- **CSP forbids `eval`** (`script-src` has no `unsafe-eval`; `hx-on::*` is banned), so error
  handling must be server-driven or use inline `addEventListener`, never `hx-on`.
- **`POST /tenants/{id}/assets` is shared.** The editor's `uploadAsset` calls it with a raw
  `fetch` (`Accept: application/json`) and depends on a **JSON 400** on failure. Any change
  must preserve that contract for the non-HTMX caller.
- We wanted **one mental model and one styling mechanism**, not snowflakes.

### Decision drivers

- **Consistency** — one rule a contributor can apply to any new create form.
- **Preserve un-reconstructable state** — never wipe a chosen file, cascade selection, or
  typed JSON on a validation error.
- **CSP-safe, no per-form JS** — prefer server-rendered HTML over bespoke client scripts.
- **Keep the editor's JSON contract** intact.
- **Errors render where the user is looking** — a field problem belongs next to its field; the
  shared dialog card is reserved for problems that are **not** tied to any field.

Two orthogonal questions follow.

## Decision 1 — Error delivery

The governing split is **field vs. non-field**:

- **Field-keyable problems are data** (an `errors` map keyed by field name). The handler
  renders them **next to their input**, never as a general banner. _How_ that render reaches
  the page follows one sub-rule — **re-render the largest region whose state the server can
  reconstruct:**
  - **Text forms** — state round-trips through `formData`, so re-render the **whole
    `createForm`** (`hx-target="this" hx-swap="outerHTML"`); the field spans come along.
  - **File / cascade forms (fonts, assets, load-test)** — the whole form is **not**
    reconstructable, so re-render **only the per-field spans** via HTMX **out-of-band** swaps:
    HTTP 200 + `reswap(HxSwap.NONE)` + an `oob(...)` fragment whose root carries
    `hx-swap-oob="true"` + a stable `id` (`#font-error-slug`, `#asset-error-catalog`,
    `#loadtest-error-testData`, …). Because the primary swap is skipped, the form, its file
    input, the cascade selections, and the typed JSON are untouched.
- **Non-field / operational problems are thrown** — a `FormInputException`, or any unmapped
  domain exception. Handlers do **not** hand-render them. The MVC error handler
  **`UiHandlerExceptionResolver`** resolves them and renders **through the view layer** (it
  returns a `ModelAndView`; the HTML comes from the `fragments/dialog :: dialogError` fragment,
  so no component hand-builds markup). `UiExceptionFilter` is only the last-resort net for
  whatever escapes the dispatch (a data/error-page response, never markup); both share
  `resolveUiError`. Content-negotiated into one envelope:
  - an HTMX form that declares a region via the **`X-Epistola-Error-Region`** request header
    (set on the create `<dialog>` via `hx-headers`, so it is absent on every other HTMX flow →
    **zero blast radius**) → **200 + `HX-Reswap: none` + an out-of-band swap** of an alert into
    a shared `#dialog-error` region that sits **inside** each `<dialog>` (so it renders within
    the modal and never touches the form);
  - a data caller (`Accept: application/json`, e.g. the editor's `uploadAsset`) → **RFC 9457
    `problem+json`** whose `detail` carries the message.

The classification is concrete per form. For **load-test**: invalid/non-object JSON
(`testData`) and a missing/invalid template/variant/version are **field** errors (per-field
OOB spans, emitted together by one `error-fields` fragment so a fixed field clears); only a
`StartLoadTest` backend failure is **non-field** and uses the `#dialog-error` card.

### Considered options (delivery)

- **Option A — field vs. non-field, graduated region (chosen).** A field problem renders next
  to its field (self-swap or OOB span per reconstructability); only a non-field/operational
  problem is thrown to the shared card. Honours the intrinsic differences while unifying the
  _principle_, and keeps errors where the user is looking.
- **Option B — one localized `#dialog-error` region for every error.** Simplest, but lumps
  field problems (bad JSON, a missing selection) into a top-of-dialog banner, away from the
  field, and throws away the per-field inline errors the text forms already have.
- **Option C — one parameterized dialog/form shell.** Fold every create form into a single
  `fragments/create-dialog.html` driven by `multipart`/`selfSwap`/`cascade` flags. Rejected:
  the fields are irreducibly per-entity and the flags turn the shell into config soup (see
  [`docs/htmx.md`](../htmx.md) → "Why per-entity `new.html`").

## Decision 2 — Error styling: `data-error` + CSS `:has()`

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
the server never toggles a parent class. The rule covers `input`, `textarea`, and `select`, so
it applies uniformly to a slug input, the load-test JSON `<textarea>`, and a cascade `<select>`.

### Considered options (styling)

- **Option S1 — `data-error` + `:has()` (chosen).** One rule, no server-side class toggling,
  works identically for self-swap and OOB. Requires `:has()` (Baseline since Firefox 121,
  Dec 2023 — the app already requires native `<dialog>`/`:modal`).
- **Option S2 — keep `.form-group.error` + `th:classappend`.** Cannot style an OOB-swapped span
  without also swapping (and resetting) the input, and leaves two mechanisms once
  fonts/assets/load-test need borders — exactly the inconsistency this work removes.

## The shared-endpoint corollary

`AssetHandler.upload` branches on `request.isHtmx`: the dialog (HTMX) gets the OOB-200 field
error for `catalog` and throws file/operational problems to the resolver; the editor (non-HTMX,
`Accept: application/json`) keeps the **JSON 400** (problem+json). One endpoint, two error
contracts, selected by caller.

## The duplicate-id field map

`FormData.executeOrFormError` (in `FormBinder.kt`) wraps a create command and maps domain
exceptions to field errors: a `ValidationException` keys under its `.field`; a
`DuplicateIdException` keys under the form's identifier field via a hardcoded
`when (entityType)`. Every text create form names that field `"slug"`, so the map sends
`environment`/`template`/`tenant`/`theme`/`attribute`/`code-list`/`stencil`/`catalog` → `"slug"`.
This is the field-keyable path for the eight text forms; load-test does not use it (it validates
and keys its fields directly in the handler).

## Consequences

**Positive**

- **The convention is build-enforced.** `CreateDialogErrorConventionTest` (in `unitTest`,
  `app.epistola.suite.architecture`) fails the build if any create dialog omits the shared
  general region/header, or if any user-editable payload field lacks its own per-field error
  span — so the silent-vanish failure (a handler keying `errors["catalog"]` with no matching span,
  which once affected five forms) cannot regress. Non-payload controls (file inputs, radio/checkbox
  groups, a cascade-only helper select) are exempt, each listed in the test with its reason.
- One rule for delivery (field → next to the field; non-field → the shared card), one CSS rule
  for styling — a new create form needs no bespoke error JS and no per-form CSS.
- File / cascade / JSON state survives a validation error (a chosen file, a load-test cascade
  selection, the typed JSON), because field errors swap only the per-field spans with
  `HX-Reswap: none`.
- A thrown error always reaches the dialog: the resolver renders into the in-modal
  `#dialog-error` region (HTMX) or `problem+json` (data callers) — there is no longer a path
  where an exception surfaces as a global 500 banner behind the open modal.
- The bespoke `htmx:responseError` JSON-parsing scripts are deleted from fonts/assets; load-test
  no longer lumps field problems into a general banner.

**Costs / things to watch**

- **The duplicate-id field map is a fragility.** An entity missing from the
  `when (entityType)` keys the error under `"id"` — a field no form renders — and the message
  **silently vanishes** (this once bit stencil). Every slug-based create entity must be listed.
- **OOB error targets need stable `id`s and must be cleared every response.** OOB only swaps
  what's sent, so a fixed field is cleared by re-sending its slot empty (`data-error` absent).
  load-test's `error-fields` fragment emits **all** field slots on each error response for this
  reason; the per-field-span forms emit every slot they own.
- **OOB targets must exist in the live DOM.** A load-test field span lives in the cascade
  fragment that is in the DOM at submit time (the populated `template-options`); a span whose
  field is not currently rendered (e.g. `versionId` when an environment is chosen) simply
  no-ops, which is harmless because that field cannot then produce an error.
- **`:has()` browser baseline** — a hard floor, acceptable given the existing `<dialog>`/`:modal`
  requirement, noted so it isn't rediscovered.

### Related

- [ADR 0007](0007-create-forms-in-modal-dialogs.md) — the decision to render create forms in modal
  dialogs at all; this ADR covers how errors render inside them.
- [`docs/htmx.md`](../htmx.md) → "Create-form error handling", "Create Forms: Modal Dialogs",
  "Why per-entity `new.html`".
- [`docs/data-testid-reference.md`](../data-testid-reference.md) — error-span `id` scheme.
- `modules/epistola-web/.../htmx/FormBinder.kt` (`executeOrFormError`, the entityType→field map),
  `apps/epistola/.../handlers/FontHandler.kt` / `AssetHandler.kt` and
  `apps/epistola/.../loadtest/LoadTestHandler.kt` (per-field OOB error responses),
  `apps/epistola/.../config/UiHandlerExceptionResolver.kt` / `UiErrorMapping.kt` /
  `UiExceptionFilter.kt` (the thrown-error pipeline),
  `apps/epistola/.../static/css/main.css` (the `:has()` rule).
- `apps/epistola/.../architecture/CreateDialogErrorConventionTest.kt` — build-time enforcement of
  this convention (per-field spans + the shared general region on every create dialog).
- [ADR 0004](0004-rfc7807-problem-details.md) — the REST API error model (a separate surface;
  these UI handlers are not REST and deliberately render HTML, not RFC 9457).
