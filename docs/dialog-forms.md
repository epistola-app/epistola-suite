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
- **Lifecycle helpers** — `dialogSuccess` / `dialogReveal` / `dialogFieldErrors`
  / `dialogFormError` on `HtmxResponseBuilder` (`modules/epistola-web/.../htmx/HtmxDsl.kt`).

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
                "showDialog" to true // host list renders the dialog into the mount when set
            }
        }
    }
}
```

`HtmxResponseBuilder.build()` already routes boosted requests (`HX-Boosted`)
through `onNonHtmx`, so the shell-level `hx-boost` navigation lands on the host
page automatically — no extra branching in the handler.

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
to. The intent: when the dialog closes (Cancel / ESC / a handler's
`HX-Trigger("closeDialog")`), restore that URL so the address bar and back button
return to the list (the list is already behind the dialog; no content swap
needed).

> **Phase 2 — history restore not yet implemented.** The `closeUrl` param and
> `data-close-url` emission exist now, but the client-side history restore is
> deliberately deferred and **implemented + tested live with the first real
> form** (there is no consumer route yet). A naive `pushState` of `closeUrl` on
> close was prototyped and removed because it is wrong in three ways that can
> only be validated against a real navigation:
>
> 1. **htmx-boost snapshot cache** — raw `pushState` bypasses htmx's history
>    snapshot, so back/forward can restore stale DOM. A correct version likely
>    uses htmx's own history API rather than raw `pushState`.
> 2. **stale `/…/new` entry** — opening pushed `/…/new` via `hx-push-url`;
>    closing should almost certainly `replaceState` back to the list rather than
>    `pushState` a third entry.
> 3. **query string** — `closeUrl` must preserve the list's filters/paging
>    (`?catalog=…`, search, page), not just its path.
>
> Phase 2 owns wiring the restore (back/forward, query-string preservation,
> htmx-boost snapshot integration) and testing it end-to-end.

### Auth (locked decision E9)

- **GET `/…/new` and POST re-check permission** (`requirePermission`) — that is
  the real gate. `UiExceptionFilter` maps a denial to the standard 403 page.
- **The trigger is hidden** for unauthorized users (`th:if`).
- **No `th:if` on the dialog fragment markup** — it is never rendered unless the
  handler already allowed it.

## Submit lifecycle (the four helpers)

The dialog's `<form>` submits with the **list** as its `hx-target`
(`hx-target="#the-list" hx-swap="outerHTML"`), the same shape catalog/variant
use. The happy path is then a plain list swap, and every error path is explicit
about not letting the swap land in the list (which would destroy the open dialog
and the user's input). The form always includes the shared error slot **inside**
the dialog: `~{epistola-web/form-error :: form-error(id='…')}`, and carries a
**stable id** (e.g. `<form id="create-environment-form" …>`) so field errors can
retarget it (see below).

| Case                                 | Helper                                                        | Effect                                                                                                                                                                                 |
| ------------------------------------ | ------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| success → close + refresh list       | `dialogSuccess(listTemplate, listFragment) { … }`             | OOB-swap the list fragment, `HX-Trigger("closeDialog")`, `HX-Reswap: none`, 200                                                                                                        |
| success → stay open (api-key reveal) | `dialogReveal(template, fragment, revealTarget) { … }`        | swap the reveal panel into the dialog (retarget + `outerHTML`), **no** `closeDialog`, 200                                                                                              |
| field errors                         | `dialogFieldErrors(template, fragment, formTarget, formData)` | re-render the `<form>` with `formData` + `errors`, `HX-Retarget` to the **form inside the dialog** (e.g. `#create-environment-form`) — not the list, not the dialog — `outerHTML`, 422 |
| operation error / uploads            | `dialogFormError(errorId, message)`                           | OOB-swap only the form-error slot, `HX-Reswap: none`, 422 — never re-renders the form body (so a `<input type=file>` survives)                                                         |

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
  cannot repopulate a file input — so they use the OOB-only `dialogFormError`.

## Verification boundary

The client open/close mechanism (open on `htmx:load` + `htmx:afterSwap`; close
removes the mount dialog so it can't be reopened) and the server helpers are
unit-tested (`DialogSkeletonFragmentTest`, `HtmxDslTest`). Deferred to Phase 2
(no consumer route yet): the **history restore on close** (see the boxed note
above) and the full **direct-navigation / shared-link / back-button** round
trip, both proven end-to-end when the first real form is converted.
