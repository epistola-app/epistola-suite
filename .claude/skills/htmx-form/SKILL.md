---
name: htmx-form
description: Create a new HTMX form page with Thymeleaf template, UI handler, routes, and search. Use when adding a new create/edit form, list page, or CRUD flow for a domain entity.
---

Create a new HTMX form flow for a domain entity.

**Input**: The domain name (e.g., "environment") and the fields it should have.

## Decision Points

Ask the user (if not already specified):

- What is the entity name?
- What fields does the form need?
- Which **form pattern** applies? (see variants below)
- Does it need search?

## Form Pattern Variants

There are **4 distinct form patterns** in the codebase. Pick the right one:

### 1. Standalone Create (modal dialog)

The create form opens in a shared modal `<dialog>` loaded from the list page over HTMX. **There is no full-page `/new`** — a direct (non-HTMX) `GET …/new` redirects to the list. This is the default for a new top-level entity.

**Reference**: `EnvironmentHandler.newForm` + `EnvironmentHandler.create` + `environments/new.html` + the trigger in `environments/list.html`.

**When**: Creating a new top-level entity (environments, themes, templates, attributes, stencils, fonts, assets, code lists, …).

**Template** (`<entity>/new.html`) — three fragments, no `content`/full page:

- **`createDialog`** — `<dialog id="create-<entity>-dialog" class="ep-dialog ep-dialog-wide" data-testid="create-<entity>-dialog">` with an `ep-dialog-header` and `~{<entity>/new :: createForm}`.
- **`createForm`** — `<form th:fragment="createForm" id="create-<entity>-form" th:hx-post="@{…}" hx-target="this" hx-swap="outerHTML" hx-boost="false">` containing `ep-dialog-body` (`~{… :: fields}`) and the **shared footer** `~{fragments/dialog :: dialogFooter('Create …', 'create-form-submit')}` (a `[data-dialog-close]` Cancel + the submit). It re-renders **itself** on validation error (`hx-target="this"`), so the dialog stays open with field errors.
- **`fields`** — the inputs (single source of truth, included by `createForm`). Any inline `<script>` lives **here** so it runs when the dialog is injected (htmx executes `<script>` in swapped content).

Fragment names must NOT be an HTML tag — use `createForm`/`createDialog`, never `form`/`dialog`. `:: form` is a markup selector that matches **every** `<form>` element and will pull extra forms into the dialog.

Give each entity its **own** `new.html` with the `createDialog`/`createForm`/`fields` fragments — do **not** factor the per-entity dialog/form/fields **shell** into one parameterized `fragments/create-dialog.html`. The shell stays per-entity (self-contained, uniform templates over DRY-but-indirected ones). Only the two pieces with **zero per-entity variation** — the list trigger and the footer — are shared, in `fragments/dialog.html` (`openTrigger` + `dialogFooter`). See [`docs/htmx.md`](../../../docs/htmx.md) → "Why per-entity `new.html`, not one shared dialog shell".

**List trigger** (`<entity>/list.html`) — use the shared `openTrigger` fragment, in both the header and the empty state:

```html
<th:block
  th:replace="~{fragments/dialog :: openTrigger(
    url=@{/tenants/{tenantId}/<entities>/new(tenantId=${tenantId})},
    label='New …', icon='plus',
    testid='<entity>-create-open',
    canCreate=${auth.has('<PERMISSION>')})}"
></th:block>
```

The fragment bakes in the nested-shell-safe attributes (`hx-target="#dialog-host" hx-swap="innerHTML" hx-boost="false"`). `hx-boost="false"` is **required**: without it the request is boosted, and the handler's non-HTMX branch redirects to the list instead of returning the dialog. The shared `#dialog-host` (in `layout/shell`) plus the open/close wiring in `fragments/htmx` auto-open the injected `<dialog>` and close it on any `[data-dialog-close]`. Use a distinct `testid` per location (`<entity>-create-open` in the header, `<entity>-create-open-empty` in the empty state).

**Handler**:

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    val tenantId = request.tenantId()
    return request.htmx {
        fragment("<entity>/new", "createDialog") { "tenantId" to tenantId.key; /* + options */ }
        onNonHtmx { redirect("/tenants/${tenantId.key}/<entities>") }   // dialog-only: no page
    }
}

fun create(request: ServerRequest): ServerResponse {
    val tenantId = request.tenantId()
    val form = request.form { /* field(...) { ... } */ }

    // On error the `createForm` fragment swaps itself in place over HTMX (dialog
    // stays open); a non-HTMX request redirects to the list.
    fun reRender(formData: Map<String, String>, errors: Map<String, String>) = request.htmx {
        fragment("<entity>/new", "createForm") {
            "tenantId" to tenantId.key
            "formData" to formData
            "errors" to errors
        }
        onNonHtmx { redirect("/tenants/${tenantId.key}/<entities>") }
    }

    if (form.hasErrors()) return reRender(form.formData, form.errors)
    val result = form.executeOrFormError { CreateEntity(tenantId = tenantId.key, ...).execute() }
    if (result.hasErrors()) return reRender(result.formData, result.errors)

    // Success navigates client-side over HTMX; falls back to 303 for non-HTMX.
    val location = "/tenants/${tenantId.key}/<entities>/..."   // detail/editor, or the list
    return if (request.isHtmx) {
        ServerResponse.ok().header("HX-Redirect", location).build()
    } else {
        ServerResponse.status(303).header("Location", location).build()
    }
}
```

**Success-step variants**: most entities `HX-Redirect` to the new item's editor/detail (templates, themes) or back to the list (environments, attributes). **API keys** instead swap a `createdReveal` fragment over the form to show the one-time secret inside the dialog. **Multipart uploads** (fonts, assets) keep their existing `hx-post` + `hx-encoding="multipart/form-data"` + inline JSON error handling — only the dialog wrapper and `newForm` change. **Catalogs** also follow this pattern (`create-catalog-dialog`), with an entity-specific submit testid (`catalog-create-submit`) and a success `HX-Redirect` to the new catalog's `…/<slug>/browse`.

**Deep-linkable (opt-in)**: every create dialog in the app makes its open state URL-addressable so it survives refresh/back/forward. Mark the `<dialog>` with `data-create-dialog` (and `data-dialog-param="upload"` for the file-upload forms; default param is `create`), `pushUrl(urlWithDialogParam(...))` in `newForm`, and render the dialog inline on `?create`/`?upload` in `list` (set a `createOpen` flag). The reconcile/close JS in `fragments/htmx` is entity-agnostic. See [`docs/htmx.md`](../../../docs/htmx.md) → "Deep-linkable create dialogs" for the full wiring.

### 2. Inline HTMX Create

Form embedded in a list/detail page that submits via HTMX and replaces a section.

**Reference**: `templates/detail.html` variant create form + `VariantRouteHandler.createVariant`

**When**: Creating a child entity inline on a parent detail page.

**Handler**: Returns an HTMX fragment (the updated section) instead of redirecting.

**Template pattern**: Form has both `th:action` (fallback) and `th:hx-post` + `hx-target` + `hx-swap="outerHTML"`.

`hx-target`/`hx-swap` are **inherited**, so a Cancel link inside such a form would inherit them. Add `hx-disinherit="hx-target hx-swap"` to the form so descendant links/controls don't inherit; the Cancel link stays a plain `<a th:href>`. Do not add per-link `hx-target="body"` overrides — see [`docs/htmx.md`](../../../docs/htmx.md) → "Create Forms: Modal Dialogs". This is the pattern for a form that needs **in-page** swaps inside a parent page; for a standalone new-entity create, use pattern 1 (the dialog). The load-test create dialog combines both: it is a dialog (pattern 1) whose form additionally uses `hx-disinherit` because its cascading selects swap a sub-region (`#template-options-section`).

### 3. Dialog Edit (HTMX PATCH + retarget/reswap)

Edit form loaded into a `<dialog>` via HTMX GET, submitted via HTMX PATCH.

**Reference**: `AttributeHandler.editForm` + `AttributeHandler.update` + `attributes/list.html` (fragment `edit-attribute-form`)

**When**: Editing an existing entity in a modal dialog.

**Handler pattern** (on validation error):

```kotlin
return request.htmx {
    fragment("attributes/list", "edit-attribute-form") {
        "tenantId" to tenantId.value
        "attribute" to attribute
        "error" to e.message           // ← standardized key
    }
    retarget("#edit-attribute-dialog-body")
    reswap(HxSwap.INNER_HTML)
}
```

### 4. HTMX Action (no form page)

A button/form that triggers an action via HTMX and replaces a section.

**Reference**: `themes/list.html` set-default form + `ThemeHandler.setDefault`

**When**: Simple actions (set-default, toggle, etc.) — no dedicated form page needed.

## Handler Location

Handlers live in the **domain package** under `apps/epistola/src/main/kotlin/app/epistola/suite/<domain>/`. Example: `EnvironmentHandler.kt` is in package `app.epistola.suite.environments`, not `app.epistola.suite.handlers`.

Routes are co-located in the same package (e.g., `EnvironmentRoutes.kt`).

## Standardized Patterns

### tenantId — always typed at method entry

Every handler method starts with:

```kotlin
val tenantId = TenantId.of(request.pathVariable("tenantId"))
```

Templates receive `tenantId.value` (String), never the wrapper. Commands/queries receive the typed `TenantId`.

### Error keys

- `errors`: `Map<String, String>` — multi-field form validation (field name → message)
- `error`: single `String` — operation-level error message

### Error display (convention — build-enforced)

Every create dialog MUST provide **both** error surfaces. This is enforced by `CreateDialogErrorConventionTest` (in `unitTest`) — a missing span or region fails the build. See [ADR 0008](../../../docs/adr/0008-create-form-validation-errors.md).

1. **A per-field error span for EVERY user-editable field** — directly inside each field's `.form-group`, after the input/hint:

   ```html
   <span
     class="form-error"
     id="<entity>-error-<field>"
     th:attr="data-error=${errors?.containsKey('<field>')}"
     th:text="${errors?.get('<field>')}"
   ></span>
   ```

   The span is always rendered; one CSS rule (`.form-group:has(.form-error[data-error='true'])`) draws the red border and `.form-error:empty` hides it until populated. Self-swap forms (pattern 1) render these inline with the re-rendered form; file/cascade forms (fonts, assets, load-test) re-send them **out-of-band** (`hx-swap-oob="true"` + `reswap(HxSwap.NONE)`) so the chosen file / cascade selections / typed text survive. **A field that keys an error but has no span → the message silently vanishes.**

2. **The shared general error region** — for non-field / operational errors. On the `<dialog>`:

   ```html
   <dialog … hx-headers='{"X-Epistola-Error-Region": "dialog-error"}'>
     <div class="ep-dialog-header">…</div>
     <th:block th:replace="~{fragments/dialog :: generalError}"></th:block>
   </dialog>
   ```

   Handlers **throw** (`FormInputException` or any domain exception) for non-field problems; `UiHandlerExceptionResolver` renders them into `#dialog-error` inside the modal (HTMX) or `problem+json` (data callers). Field problems stay **data** (the `errors` map). See [`docs/htmx.md`](../../../docs/htmx.md) → "Create-form error handling".

Only controls that are not part of the create payload are exempt (file inputs, radio/checkbox choice groups, a cascade-only helper select); everything submitted gets a span.

### Delete pattern

All list/detail page deletes use `openConfirmDialog()`:

```html
<button
  type="button"
  class="ep-btn ep-btn-ghost ep-btn-ghost-destructive ep-btn-sm ep-btn-icon"
  data-confirm-title="Delete <Entity>"
  th:attr="data-confirm-message='...', data-confirm-url=@{...}"
  data-confirm-target="#<entity>-rows"
  onclick="openConfirmDialog(this)"
></button>
```

Include the confirm dialog fragment: `<th:block th:replace="~{fragments/confirm-dialog :: confirm-dialog}" />`

Use `data-confirm-swap="outerHTML"` when the target ID matches the returned fragment's root element ID (e.g., `#variants-section`).

Exception: template delete in the danger zone keeps `confirm()` with `hx-boost="false"` (full-page navigation).

### HTMX DSL

Reference: `HtmxDsl.kt` — `apps/epistola/.../htmx/HtmxDsl.kt`

```kotlin
return request.htmx {
    fragment("template/path", "fragment-name") {
        "key" to value                    // ModelBuilder DSL
    }
    oob("template/path", "oob-fragment") { // Out-of-Band swap
        "key" to value
    }
    retarget("#css-selector")             // Override hx-target
    reswap(HxSwap.INNER_HTML)             // Override hx-swap
    trigger("event-name")                 // Trigger client-side event
    pushUrl("/new/url")                   // Push URL to browser history
    onNonHtmx { redirect("/fallback") }   // Non-HTMX fallback
}
```

### Thymeleaf conventions

- Use `th:hx-*` (not `hx-*`) when the attribute value needs Thymeleaf expression processing (e.g., URL building): `th:hx-get="@{/tenants/{id}/...}"`
- Use plain `hx-*` for static values: `hx-target="#rows"`, `hx-swap="innerHTML"`
- Use `hx-boost="false"` on links/forms that should NOT be intercepted by HTMX (e.g., full-page navigation like the editor link or danger zone delete)
- Card wrapper: `<div class="detail-section">` with `detail-section-header` + `detail-section-body`
- Multi-line text: use `<textarea class="ep-textarea">` (not `ep-input`)
- Search: use the reusable fragment `fragments/search :: search-box(placeholder, searchUrl, targetId)`

### Routes

```kotlin
@Configuration
class <Entity>Routes(private val handler: <Entity>Handler) {
    @Bean
    fun <entity>RouterFunction(): RouterFunction<ServerResponse> = router {
        "/tenants/{tenantId}/<entities>".nest {
            GET("", handler::list)
            GET("/search", handler::search)
            GET("/new", handler::newForm)
            POST("", handler::create)
            POST("/{<entity>Id}/delete", handler::delete)
        }
    }
}
```

UI deletes always use `POST /{id}/delete`. The `DELETE` HTTP verb is only used for JS-called endpoints (e.g., data example deletion from schema-manager).

## Checklist

- [ ] Handler in `apps/epistola/.../suite/<domain>/<Entity>Handler.kt`
- [ ] Routes in `apps/epistola/.../suite/<domain>/<Entity>Routes.kt`
- [ ] Template(s) in `apps/epistola/src/main/resources/templates/<entity>/`
- [ ] **Per-field error span** (`<entity>-error-<field>`) on every payload field + the shared `generalError` region with the `X-Epistola-Error-Region` header — enforced by `CreateDialogErrorConventionTest`
- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew integrationTest`

## Gotchas

- The `request.htmx { }` DSL requires importing `app.epistola.suite.htmx.htmx`
- `redirect()` requires importing `app.epistola.suite.htmx.redirect`
- `.execute()` and `.query()` require importing `app.epistola.suite.mediator.execute` / `.query`
- Templates inside the HTMX DSL `fragment()` use the `"key" to value` syntax (infix `ModelBuilder.to`), not `mapOf()`
- The `reRender(formData, errors)` inner function (re-rendering the `createForm` fragment on validation error) is a standard pattern — keep it local to the `create`/`update` method
- Create forms are **dialog-only** (pattern 1): `newForm` returns the `createDialog` fragment for HTMX and `redirect`s to the list otherwise — never render a `…/new` page. List/detail pages still use full-page renders (`ServerResponse.ok().page("...")`); fragments use `request.htmx { fragment(...) }`
- **`form.formData` includes ALL submitted params**, not just fields declared via `field()`. Declared fields get trimmed and validated; undeclared fields (e.g., hidden inputs like `sourceUrl`, `consoleLogs`) are passed through as-is. Access them via `form.formData["fieldName"]`.
