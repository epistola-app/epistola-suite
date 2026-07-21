# Dialog forms

## Summary

Create/upload forms in the UI are **server-sent, URL-addressable dialogs**: one
shared `ep-dialog` shell, a small family of lifecycle DSL helpers, and a single
open/close mechanism. This document is the convention every such form follows.
It is the shared foundation built in Phase 1 (Chunks 1 & 2); the 14 real forms
are converted onto it in later phases.

The pieces:

- **Shell fragment** — `epistola-web/dialog :: dialog-shell` (chrome only; the
  caller owns the `<form>`). Helpers `dialog-body`, `dialog-footer`,
  `dialog-mount`. See the fragment's own comments.
- **Open/close** — server-driven, in `static/js/behaviors.js` (open) and
  `static/js/app-shell.js` (close).
- **Lifecycle helpers** — `dialogSuccess` / `dialogLocation` / `dialogRedirect` /
  `dialogReveal` / `dialogFieldErrors` / `dialogFormError` / `dialogFieldErrorsOob`
  on `HtmxResponseBuilder` (`modules/epistola-web/.../htmx/HtmxDsl.kt`).

## Route convention (URL-addressable)

Every form keeps a real route `GET /…/new` that renders the **same dialog
fragment** two ways off one handler (which also owns prefill):

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    val tenantId = request.tenantId()
    requirePermission(tenantId.key, Permission.TENANT_SETTINGS) // auth: re-check in GET (E9)
    return request.htmx {
        // In-app trigger (hx-get, not boosted): return just the dialog fragment,
        // which HTMX swaps into #dialog-mount and behaviors.js opens.
        fragment("environments/dialog", "environment-dialog") {
            "tenantId" to tenantId.key
            // + prefill (catalogs, attribute descriptors, …)
        }
        // Direct navigation / shared link / hx-boost: render the HOST page (the
        // list) with the dialog fragment embedded in its #dialog-mount as a
        // PLAIN <dialog> — behaviors.js opens it on htmx:load. The list is
        // visible behind it.
        onNonHtmx {
            page("environments/list") {
                "tenantId" to tenantId.key
                "environments" to ListEnvironments(tenantId = tenantId).query()
                "openDialog" to true // host list renders the dialog into the mount when set
            }
        }
    }
}
```

`HtmxResponseBuilder.build()` already routes boosted requests (`HX-Boosted`)
through `onNonHtmx`, so the shell-level `hx-boost` navigation lands on the host
page automatically — no extra branching in the handler.

### Host-page embed: the dialog mount

The host list page drops the shared mount once and renders the dialog into it
**only** on direct navigation, gated by the handler's `openDialog` flag:

```html
<div id="dialog-mount" data-dialog-mount>
  <th:block th:if="${openDialog}">
    <th:block th:replace="~{environments/new :: dialog}"></th:block>
  </th:block>
</div>
```

On a plain list load `openDialog` is unset, so the mount stays empty; the
in-app trigger swaps the fragment in later (hx-get → `#dialog-mount`), and
`behaviors.js` opens whatever dialog lands in the mount.

> **Gotcha — never put `th:if`/`th:unless` and `th:replace`/`th:insert` on the
> SAME element.** `th:replace`/`th:insert` (attribute precedence 100) run
> **before** `th:if`/`th:unless` (precedence 300), so the fragment is included
> **unconditionally** and the guard is silently ignored. Always wrap the
> conditional guard on an **OUTER** element and put the `th:replace`/`th:insert`
> on an inner one (as above). Writing
> `<th:block th:if="${openDialog}" th:replace="…">` embeds the dialog on every
> page load, and `behaviors.js` then pops it open every time. This mount is the
> exemplar every form copies — keep the guard on the outer element.

### Trigger markup

The list/detail page's "New …" button:

```html
<a
  th:if="${auth.has('TENANT_SETTINGS')}"
  th:hx-get="@{/tenants/{tenantId}/environments/new(tenantId=${tenantId})}"
  hx-target="#dialog-mount"
  hx-swap="innerHTML"
  hx-push-url="true"
>
  New Environment
</a>
```

- `hx-target="#dialog-mount" hx-swap="innerHTML"` — the dialog lands in the
  shared mount (`~{epistola-web/dialog :: dialog-mount}`, dropped once per page).
- `hx-push-url="true"` — opening updates the address bar to the shareable
  `/…/new` URL.
- `th:if="${auth.has(...)}"` — **hide the trigger for unauthorized users** (E9);
  do not render a button that would 403.

### Close pushes the URL back to the list

The dialog shell is rendered with `closeUrl` = the list URL:

```html
<div
  th:replace="~{epistola-web/dialog :: dialog-shell(
    dialogId='create-environment-dialog',
    title='New Environment',
    closeUrl=@{/tenants/{tenantId}/environments(tenantId=${tenantId})},
    content=~{::environment-form})}"
></div>
```

That emits `data-close-url` on the `<dialog>` — the list URL the dialog belongs
to. When the dialog closes (Cancel / ESC / a handler's
`HX-Trigger("closeDialog")`), the address bar and back button return to the list
(the list is already behind the dialog; no content swap needed).

**The mechanism (implemented — the open/close history split):**

- **Open → push.** The trigger carries `hx-push-url="true"` (see "Trigger
  markup" above), so opening pushes the `/…/new` URL through htmx's own history
  API. Using `hx-push-url` rather than a raw `pushState` keeps htmx's boost
  history **snapshot** consistent, so a later Back/Forward restores the correct
  DOM (the list) instead of stale markup.
- **Close → replace.** The capture-phase `close` listener in
  `static/js/behaviors.js` (which also removes the dismissed mount dialog) reads
  `data-close-url` and restores it with
  `history.replaceState(history.state, '', closeUrl)` **before** removing the
  dialog. It uses `replaceState`, **not** `pushState`: opening already pushed the
  `/…/new` entry, so closing must overwrite it (the two history states stay
  `[list, /…/new]`) rather than add a redundant third entry. The listener only
  replaces when the current path actually differs from the close-url path, to
  avoid needless history churn.
- **Back → native.** Pressing Back is handled entirely by htmx's boosted
  `popstate`/snapshot restore — it pops back to the list URL and restores the
  list snapshot (which has an empty mount), so the dialog is gone. No extra
  client code is needed for the back button; it is covered end-to-end by
  `EnvironmentDialogUiTest`.

> **Known limitation — query string.** `closeUrl` is the list **path** only, so
> a list that carries filters/paging in its query string (`?catalog=…`, search,
> page) would lose them on close. The environment list has no such filters, so
> this is a non-issue there; a filtered list adopting this convention will need
> `closeUrl` to preserve (or the close handler to merge) the query string.

### Auth (locked decision E9)

- **GET `/…/new` and POST re-check permission** (`requirePermission`) — that is
  the real gate. `UiExceptionFilter` maps a denial to the standard 403 page.
- **The trigger is hidden** for unauthorized users (`th:if`).
- **No `th:if` on the dialog fragment markup** — it is never rendered unless the
  handler already allowed it.

## Submit lifecycle (the seven helpers)

The dialog's `<form>` submits with the **list** as its `hx-target`
(`hx-target="#the-list" hx-swap="outerHTML"`), the same shape catalog/variant
use. The happy path is then a plain list swap, and every error path is explicit
about not letting the swap land in the list (which would destroy the open dialog
and the user's input). The form always includes the shared error slot **inside**
the dialog: `~{epistola-web/form-error :: form-error(id='…')}`, and carries a
**stable id** (e.g. `<form id="create-environment-form" …>`) so field errors can
retarget it (see below).

| Case                                        | Helper                                                        | Effect                                                                                                                                                                                                 |
| ------------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| success → close + refresh list              | `dialogSuccess(listTemplate, listFragment) { … }`             | OOB-swap the list fragment, `HX-Trigger` with `closeDialog` + `dialogSuccess` (the distinct success event the app-shell search-box reset listens on), `HX-Reswap: none`, 200                           |
| success → navigate to created resource      | `dialogLocation(url)`                                         | `HX-Location: <url>`, 200, no fragment — a **soft** boosted body-swap navigates to the created thing (the same nav an in-app link click does; no full reload); pair with `onNonHtmx { redirect(url) }` |
| success → navigate with a **full reload**   | `dialogRedirect(url)`                                         | `HX-Redirect: <url>`, 200, no fragment — a hard full-page reload. Only when the destination genuinely needs a fresh document (head assets boost won't reload); otherwise prefer `dialogLocation`       |
| success → stay open (api-key reveal)        | `dialogReveal(template, fragment, revealTarget) { … }`        | swap the reveal panel into the dialog (retarget + `outerHTML`), **no** `closeDialog`, 200                                                                                                              |
| field errors (standard forms)               | `dialogFieldErrors(template, fragment, formTarget, formData)` | re-render the `<form>` with `formData` + `errors`, `HX-Retarget` to the **form inside the dialog** (e.g. `#create-environment-form`) — not the list, not the dialog — `outerHTML`, 422                 |
| field errors (**uploads** — file inputs)    | `dialogFieldErrorsOob(template, fragment, errors)`            | OOB-swap only the per-field `field-error` **spans**, `HX-Reswap: none`, 422 — the form body (and its `<input type=file>` + added rows) is never re-rendered                                            |
| operation error / generic 5xx (global slot) | `dialogFormError(errorId, message)`                           | OOB-swap only the single global form-error slot, `HX-Reswap: none`, 422                                                                                                                                |

Each pairs with an `onNonHtmx { }` fallback (redirect to the list on success;
`page(422, …)` re-rendering the host page with the dialog + errors on failure) —
see the KDoc on each helper.

Why the split matters (see also the "Error handling" section of `FORMS_PLAN.md`):

- The **global slot** (`dialogFormError` / `globalFormError`) and the **generic
  5xx** client safety net (`app-shell.js`) both write into the in-dialog
  `[data-form-error]` slot with the list untouched.
- **Field errors** default to `outerHTML`, which — because the form targets the
  list — would replace the list and nuke the dialog; `dialogFieldErrors`
  retargets to the **`<form>` inside the dialog** (its stable id, e.g.
  `#create-environment-form`) and re-renders that form in place. It must NOT
  retarget the `<dialog>` element: `outerHTML`-swapping an open modal dialog
  removes it from the top layer and drops in a fresh, plain (closed) `<dialog>`
  that nothing reopens (the swap targets the dialog id, not the mount) — the
  dialog would lose its backdrop / close on every validation error. Targeting
  the inner form leaves the `<dialog>` untouched, matching `dialogReveal`.
- **Uploads** (font, image) must never re-render the form body — the browser
  cannot repopulate a file input — so their **field** errors take a separate
  track (`dialogFieldErrorsOob`, below), and a genuine operation-level failure
  falls to the global slot (`dialogFormError`).

### Uploads: the per-field OOB error track

The multipart upload forms (font, image) can't use `dialogFieldErrors`: it
re-renders the form body, and a browser cannot repopulate `<input type=file>`
(the font form additionally builds face rows in JS with no hydration), so the
user's file selection and any added rows would be wiped on every validation
error. Instead they carry per-field error **spans** with stable ids and swap
only those:

- **Same error model as the standard forms** — a `field → message` map — but with
  any repeating group folded into ONE **aggregate key** (font uses `faces`;
  per-row errors are out of scope, mirroring code-list's `errors.entries`). Font
  ends up with five keys (`catalog`, `slug`, `name`, `kind`, `faces`); image with
  two (`catalog`, `file` — everything file-related folds into `file`).
- **The span fragment** — `epistola-web/form-error :: field-error(id, message, oob)`.
  It renders one inline `.form-error` span. The form embeds each with `oob=false`
  (plain, empty on first render); the handler's error response re-renders the
  **same ids** with `oob=true` (carrying `hx-swap-oob`) via a `field-errors`
  fragment. Because the form body is never touched, the handler MUST emit **every**
  span each time (empty where resolved) so a fixed field's stale message clears.
- **The helper** — `dialogFieldErrorsOob(template, "field-errors", errors)`:
  `oob`-renders the spans, `HX-Reswap: none` (no primary swap), 422. The in-dialog
  global `form-error` slot is left in place for the 5xx safety net.
- **The message is the affordance.** The re-rendering forms outline the field via
  a `.form-group.error` class stamped on during re-render; uploads never
  re-render, so there is no red box outline — the inline red `.form-error` message
  is the signal.

> **Dual-purpose endpoint caveat.** The `POST /…/images` (and `/…/fonts`) route
> serves BOTH this browser dialog AND the Lit editor's asset picker, which POSTs
> the same multipart with `Accept: application/json` and no HTMX headers. The
> handler branches on `request.isHtmx`: HTMX → the dialog helpers above; non-HTMX
> → the **unchanged JSON contract** (first error as a `400 {"error": …}`, success
> as a JSON body). Keep both paths — `FontUploadHandlerTest` / `AssetRoutesTest`
> cover the JSON one, `FontDialogHandlerHtmxTest` / `ImageDialogHandlerHtmxTest`
> the dialog one.

> **Auth deviation (fonts/images).** Unlike the E9 convention above, the
> font/image handlers currently gate **nothing** (there is no `FONT_EDIT` /
> `ASSET_EDIT` permission), so their triggers are not `th:if`-hidden and the GET/
> POST are not `requirePermission`-guarded. This mirrors the pre-conversion
> behavior; revisit if a per-surface permission is introduced.

## Verification boundary

The client open/close mechanism (open on `htmx:load` + `htmx:afterSwap`; close
removes the mount dialog so it can't be reopened) and the server helpers are
unit-tested (`DialogSkeletonFragmentTest`, `HtmxDslTest` — including
`dialogFieldErrorsOob`). The **URL-addressable history binding** — open pushes
`/…/new`, Cancel/ESC `replaceState`-restores the list URL, and Back returns to
the list with the dialog closed — is proven end-to-end by
`EnvironmentDialogUiTest` (the first converted form). The **upload per-field OOB
track** is covered at the handler level by `FontDialogHandlerHtmxTest` /
`ImageDialogHandlerHtmxTest` (dialog branches: GET fragment vs host page, OOB
field error, close+refresh) with the JSON/editor contract held by
`FontUploadHandlerTest` / `AssetRoutesTest`, and end-to-end in the browser by
`GlobalFormErrorUiTest` (the faces error fills its span while the dialog stays
open).
