# HTMX Utilities for WebMvc.fn

This document describes the custom HTMX utilities built for Epistola Suite. These utilities integrate HTMX with Spring WebMvc.fn functional endpoints, providing a type-safe Kotlin DSL for building HTMX responses.

## Overview

The `htmx` package provides:

- **Request extensions** - Detect HTMX requests and read HTMX headers
- **Simple render helper** - Quick HTMX-aware template rendering
- **Kotlin DSL** - Advanced response building with multiple fragments, OOB swaps, and response headers

## Quick Start

### Simple Case: Single Fragment

For basic HTMX responses where you need to render a fragment for HTMX requests and redirect for regular requests:

```kotlin
fun create(request: ServerRequest): ServerResponse {
    // ... business logic ...

    return request.render(
        template = "templates/list",
        fragment = "rows",
        model = mapOf("templates" to templates),
        redirectOnSuccess = "/templates"
    )
}
```

### Advanced Case: DSL

For complex scenarios with multiple fragments, conditional logic, or response headers:

```kotlin
fun create(request: ServerRequest): ServerResponse {
    // ... business logic ...

    return request.htmx {
        fragment("templates/list", "rows") {
            "templates" to templates
        }
        trigger("templateCreated")
        onNonHtmx { redirect("/templates") }
    }
}
```

## Request Extensions

The `HtmxRequest.kt` file provides extension properties and methods on `ServerRequest`:

### HTMX Header Detection

```kotlin
// Check if this is an HTMX request
if (request.isHtmx) {
    // Handle HTMX request
}

// Read HTMX headers
val triggerId = request.htmxTrigger      // HX-Trigger header
val targetId = request.htmxTarget        // HX-Target header
val currentUrl = request.htmxCurrentUrl  // HX-Current-URL header
val isBoosted = request.htmxBoosted      // HX-Boosted header
```

| Property                    | Header                       | Description                                    |
| --------------------------- | ---------------------------- | ---------------------------------------------- |
| `isHtmx`                    | `HX-Request`                 | True if request was made by HTMX               |
| `htmxTrigger`               | `HX-Trigger`                 | ID of the element that triggered the request   |
| `htmxTriggerName`           | `HX-Trigger-Name`            | Name of the element that triggered the request |
| `htmxTarget`                | `HX-Target`                  | ID of the target element                       |
| `htmxCurrentUrl`            | `HX-Current-URL`             | Current URL of the browser                     |
| `htmxBoosted`               | `HX-Boosted`                 | True if request is via hx-boost                |
| `htmxHistoryRestoreRequest` | `HX-History-Restore-Request` | True if restoring history                      |
| `htmxPrompt`                | `HX-Prompt`                  | User response to hx-prompt                     |

### Parameter Retrieval Helpers

Clean, concise parameter extraction without boilerplate:

#### Path Variable Extraction with Validation

```kotlin
// Extract path variable and validate in one expression
val themeId = request.pathId("themeId") { ThemeId.validateOrNull(it) }
    ?: return ServerResponse.badRequest().build()

// Replaces verbose:
// val themeIdStr = request.pathVariable("themeId")
// val themeId = ThemeId.validateOrNull(themeIdStr) ?: return ...
```

#### Query Parameter Retrieval

```kotlin
// Nullable string parameter
val searchTerm = request.queryParam("q")  // null if not present

// With default value
val pageSize = request.queryParam("size", "10")  // "10" if not present

// Integer parameters with default
val offset = request.queryParamInt("offset", 0)
val limit = request.queryParamInt("limit", 100)

// Replaces verbose:
// val offset = request.param("offset").orElse("0").toInt()
// val limit = request.param("limit").orElse("100").toInt()
```

#### EntityId Usage (toString() Integration)

All EntityIds (TenantId, ThemeId, TemplateId, etc.) have `toString()` implemented, so they can be used directly without `.value`:

```kotlin
// In handlers:
return ServerResponse.ok().page("themes/list") {
    "tenantId" to tenantId        // toString() called automatically
    "themes" to themes
}

// In redirects:
redirect("/tenants/${tenantId}/themes")  // No .value needed

// This works because EntityId.toString() returns the underlying value
// TenantId("my-tenant").toString() → "my-tenant"
// ThemeId("dark").toString() → "dark"
```

## Simple Render Helper

For straightforward cases, use `request.render()`:

```kotlin
// HTMX request → renders fragment
// Non-HTMX request → redirects
return request.render(
    template = "templates/list",
    fragment = "rows",
    model = mapOf("items" to items),
    redirectOnSuccess = "/list"
)

// Always render (no redirect)
return request.renderTemplate(
    template = "templates/list",
    fragment = "rows",
    model = mapOf("items" to items)
)
```

## Kotlin DSL

The DSL provides full control over HTMX responses.

### Basic Structure

```kotlin
return request.htmx {
    // Primary fragment (replaces hx-target)
    fragment("template", "fragmentName") {
        "key" to value
    }

    // Non-HTMX fallback
    onNonHtmx { redirect("/url") }
}
```

### Multiple Fragments (Out-of-Band Swaps)

Update multiple parts of the page in a single response:

```kotlin
return request.htmx {
    // Primary fragment
    fragment("items/list", "table-rows") {
        "items" to items
    }

    // Out-of-band updates
    oob("layout/header", "item-count") {
        "count" to items.size
    }
    oob("components/toast", "success") {
        "message" to "Item saved!"
    }
}
```

The OOB fragments must have corresponding elements with matching IDs in your HTML:

```html
<!-- Primary target -->
<tbody id="table-rows" hx-swap-oob="true">
  ...
</tbody>

<!-- OOB targets (updated automatically) -->
<span id="item-count" hx-swap-oob="true">...</span>
<div id="toast" hx-swap-oob="true">...</div>
```

### Conditional Rendering

Use standard Kotlin control flow:

```kotlin
return request.htmx {
    if (errors.isEmpty()) {
        fragment("items/list", "rows") {
            "items" to items
        }
        oob("components/toast", "success") {
            "message" to "Saved successfully!"
        }
        trigger("itemSaved")
    } else {
        fragment("items/form", "validation-errors") {
            "errors" to errors
        }
        reswap(HxSwap.NONE)  // Don't swap, just show errors
    }

    onNonHtmx { redirect("/items") }
}
```

### Response Headers

Control HTMX behavior with response headers:

```kotlin
return request.htmx {
    fragment("items/list", "rows") { "items" to items }

    // Trigger client-side events
    trigger("itemCreated")
    trigger("showNotification", """{"message": "Saved!"}""")

    // Update browser URL
    pushUrl("/items?page=2")      // Adds to history
    replaceUrl("/items?page=2")   // Replaces current entry

    // Override swap behavior
    reswap(HxSwap.OUTER_HTML)
    retarget("#other-element")
}
```

### Swap Modes

The `HxSwap` enum provides all HTMX swap modes:

| Value          | Description                  |
| -------------- | ---------------------------- |
| `INNER_HTML`   | Replace inner HTML (default) |
| `OUTER_HTML`   | Replace entire element       |
| `BEFORE_BEGIN` | Insert before the element    |
| `AFTER_BEGIN`  | Insert as first child        |
| `BEFORE_END`   | Insert as last child         |
| `AFTER_END`    | Insert after the element     |
| `DELETE`       | Delete the element           |
| `NONE`         | Don't swap (useful with OOB) |

## Complete Example

Here's a complete handler demonstrating various features:

```kotlin
@Component
class ItemHandler(
    private val itemService: ItemService,
) {
    fun list(request: ServerRequest): ServerResponse {
        val items = itemService.findAll()
        return ServerResponse.ok().render("items/list", mapOf("items" to items))
    }

    fun create(request: ServerRequest): ServerResponse {
        val form = request.params()
        val result = itemService.create(
            name = form.getFirst("name") ?: throw IllegalArgumentException("Name required"),
            description = form.getFirst("description"),
        )

        return request.htmx {
            when (result) {
                is Success -> {
                    val items = itemService.findAll()
                    fragment("items/list", "table-body") {
                        "items" to items
                    }
                    oob("components/toast", "success") {
                        "message" to "Item '${result.item.name}' created!"
                    }
                    trigger("itemCreated", """{"id": ${result.item.id}}""")
                }
                is ValidationError -> {
                    fragment("items/form", "errors") {
                        "errors" to result.errors
                    }
                    reswap(HxSwap.NONE)
                }
            }
            onNonHtmx { redirect("/items") }
        }
    }

    fun delete(request: ServerRequest): ServerResponse {
        val id = request.pathVariable("id").toLong()
        itemService.delete(id)

        return request.htmx {
            // Empty fragment to remove the row
            reswap(HxSwap.DELETE)
            trigger("itemDeleted", """{"id": $id}""")
            onNonHtmx { redirect("/items") }
        }
    }
}
```

## Template Fragments

Define reusable fragments in your Thymeleaf templates:

```html
<!-- items/list.html -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
  <head>
    ...
  </head>
  <body>
    <main>
      <h1>Items</h1>

      <table>
        <thead>
          ...
        </thead>
        <tbody id="table-body" th:fragment="table-body">
          <tr th:each="item : ${items}">
            <td th:text="${item.id}"></td>
            <td th:text="${item.name}"></td>
            <td>
              <button hx-delete="/items/${item.id}" hx-target="closest tr" hx-swap="delete">
                Delete
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </main>
  </body>
</html>
```

```html
<!-- components/toast.html -->
<div th:fragment="success" id="toast" class="toast success" hx-swap-oob="true">
  <span th:text="${message}"></span>
</div>
```

## File Structure

```
app/epistola/suite/htmx/
├── HtmxRequest.kt         # HTMX headers (isHtmx, htmxBoosted, etc.)
│                          # Parameter helpers: pathId(), queryParam(), queryParamInt()
├── HtmxRender.kt          # render(), renderTemplate(), page() shortcut, htmx {} entry point
├── HtmxDsl.kt             # DSL builders (HtmxResponseBuilder, NonHtmxBuilder, ModelBuilder)
│                          # formError() helper, onNonHtmx overloads
├── HtmxSwap.kt            # HxSwap enum
├── HtmxFragmentsView.kt   # Multi-fragment view rendering
└── FormBinder.kt          # Form validation DSL (field specs, validators)
                           # FormData with typed accessors
                           # executeOrFormError() for exception mapping

resources/templates/
└── fragments/
    └── form-fields.html   # Reusable form field macros (text, textarea, select, checkbox)
```

## Best Practices

1. **Use simple `render()` for basic cases** - Don't use the DSL when you just need a single fragment with redirect fallback.

2. **Keep fragments small** - Design fragments as reusable, self-contained pieces of UI.

3. **Always provide `onNonHtmx`** - Ensure your app works without JavaScript (progressive enhancement).

4. **Use OOB sparingly** - Out-of-band updates are powerful but can make debugging harder. Use them for notifications, counters, and status updates.

5. **Trigger events for coordination** - Use `trigger()` to notify other parts of the page about changes, rather than updating everything server-side.

## Modern HTMX + Thymeleaf Patterns

These patterns reduce boilerplate and improve code consistency across handlers and templates.

### Idea A: Shell Page Shortcut

Eliminate repetitive `render("layout/shell", mapOf(...))` boilerplate with the `page()` extension:

**Before:**

```kotlin
return ServerResponse.ok().render(
    "layout/shell",
    mapOf(
        "contentView" to "environments/list",
        "pageTitle" to "Environments - Epistola",
        "tenantId" to tenantId.value,
        "environments" to environments,
    ),
)
```

**After:**

```kotlin
return ServerResponse.ok().page("environments/list") {
    "pageTitle" to "Environments - Epistola"
    "tenantId" to tenantId.value
    "environments" to environments
}
```

The `page()` extension automatically wraps your content view inside `layout/shell` with the provided model attributes.

### Idea E: Thymeleaf Form Field Macros

Reusable form field fragments reduce boilerplate by 80%. Instead of repeating 4-5 lines per field:

**Before (environments/new.html):**

```html
<div class="form-group" th:classappend="${errors?.containsKey('slug')} ? 'error' : ''">
  <label class="ep-label" for="slug">Environment ID</label>
  <input
    type="text"
    id="slug"
    name="slug"
    class="ep-input"
    required
    pattern="^[a-z][a-z0-9]*(-[a-z0-9]+)*$"
    minlength="3"
    maxlength="30"
    th:value="${formData?.slug}"
  />
  <span class="form-hint">3-30 characters, lowercase letters, numbers, and hyphens</span>
  <span class="form-error" th:if="${errors?.containsKey('slug')}" th:text="${errors.slug}"></span>
</div>
```

**After (using form-fields.html macros):**

```html
<th:block
  th:replace="~{fragments/form-fields :: text-field(
    'slug', 'Environment ID', 'production', '3-30 characters', true,
    '^[a-z][a-z0-9]*(-[a-z0-9]+)*$', 3, 30
)}"
/>
```

Available macros: `text-field`, `textarea-field`, `select-field`, `checkbox-field`, `form-actions`.

### Idea C & D: Form Validation DSL + Exception Mapping

Type-safe form validation with automatic exception-to-error mapping:

```kotlin
val form = request.form {
    field("slug") {
        required()
        asEnvironmentId()  // Validates as EnvironmentId format
    }
    field("name") {
        required()
        maxLength(100)
    }
}

// Re-render the `createForm` fragment on error: over HTMX it swaps itself in
// place (hx-target="this"), keeping the dialog open; non-HTMX redirects to the list.
fun reRender(formData: Map<String, String>, errors: Map<String, String>) = request.htmx {
    fragment("environments/new", "createForm") {
        "tenantId" to tenantId.key
        "formData" to formData
        "errors" to errors
    }
    onNonHtmx { redirect("/tenants/${tenantId.key}/environments") }
}

if (form.hasErrors()) return reRender(form.formData, form.errors)

val environmentId = form.getEnvironmentId("slug")!!
val name = form["name"]

// Execute command and automatically map exceptions to form errors
val result = form.executeOrFormError {
    CreateEnvironment(id = environmentId, tenantId = tenantId, name = name).execute()
}

if (result.hasErrors()) return reRender(result.formData, result.errors)
```

**Supported validators:** `required()`, `pattern(regex)`, `minLength(n)`, `maxLength(n)`, `min(n)`, `max(n)`

**Domain ID validators:** `asEnvironmentId()`, `asTemplateId()`, `asVariantId()`, `asVersionId()`, `asTenantId()`

**Type coercion:** `asInt()`, and typed accessors like `form.getInt("count")`, `form.getEnvironmentId("envId")`

**Exception mapping:** Automatically converts `ValidationException` and `DuplicateIdException` to field errors.

### Idea B: Unified Page + Fragment DSL

Eliminate `if (!request.isHtmx)` branches by handling the non-HTMX case inside the htmx DSL. The
`onNonHtmx { }` block supports both `page()` (list/detail pages) and `redirect()` (create dialogs,
whose `…/new` is dialog-only):

```kotlin
// Create dialog: fragment for HTMX, redirect to the list otherwise.
fun newForm(request: ServerRequest): ServerResponse = request.htmx {
    fragment("<entity>/new", "createDialog") { "tenantId" to tenantId.key }
    onNonHtmx { redirect("/tenants/${tenantId.key}/<entities>") }
}

// List/detail endpoint that also serves a fragment: render the full page on non-HTMX.
fun list(request: ServerRequest): ServerResponse = request.htmx {
    fragment("<entity>/list", "rows") { "items" to items }
    onNonHtmx { page("<entity>/list") { "items" to items } }
}
```

This unifies request handling in a single DSL scope instead of a leading `if (!request.isHtmx)` branch.

### Idea F: HTMX Form Error Response Helper

Simplify inline form error responses with the `formError()` helper:

**Before (verbose):**

```kotlin
return request.htmx {
    fragment("tenants/list", "create-form") {
        "formData" to formData.formData
        "errors" to formData.errors
    }
    retarget("#create-form")
    reswap(HxSwap.OUTER_HTML)
}
```

**After (concise):**

```kotlin
return request.htmx {
    formError("tenants/list", "create-form", formData)
    retarget("#create-form")  // Optional customization
    onNonHtmx { redirect("/tenants") }
}
```

The `formError()` helper:

- Automatically spreads `formData.formData` and `formData.errors`
- Sets `HxSwap.OUTER_HTML` as default
- Works with any `FormData` object from the form validation DSL
- Allows further customization with `retarget()`, `trigger()`, `onNonHtmx()`, etc.

## Common Patterns

### Serving Fragments and Pages from One Endpoint

When `hx-boost="true"` is on `<body>`, all link navigation sends `HX-Request: true` with
`HX-Boosted: true`. A create-form `newForm` is dialog-only: it returns the dialog fragment for HTMX
and redirects to the list otherwise.

```kotlin
fun newForm(request: ServerRequest): ServerResponse = request.htmx {
    fragment("<entity>/new", "createDialog") { "tenantId" to tenantId.key }
    onNonHtmx { redirect("/tenants/${tenantId.key}/<entities>") }   // boosted/no-JS → list
}
```

A **dual-purpose** endpoint that serves both the dialog and in-page fragment updates (the
`loadtest/new` cascade) distinguishes them by `HX-Trigger-Name` — the cascade `<select>`s each carry
a field name; the dialog-open trigger `<a>` carries none:

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    if (!request.isHtmx) return redirect("/tenants/$tenantKey/load-tests")
    val cascadeFields = setOf("templateId", "variantId", "versionId", "environmentId", "exampleId")
    if (request.htmxTriggerName !in cascadeFields) {
        return ServerResponse.ok().render("loadtest/new :: createDialog", model)   // open the dialog
    }
    // otherwise: a cascade update → return the template-options fragment
}
```

For an endpoint that serves a **full page** plus fragments (list/detail, not create), still guard on
`!request.isHtmx || request.htmxBoosted` before rendering `layout/shell`, so boosted navigation
receives the page and not a bare fragment.

### Create Forms: Modal Dialogs

Standalone "create new entity" forms open in a **shared modal `<dialog>`** loaded from the list
page over HTMX. There is **no full-page `…/new`** — a direct (non-HTMX) `GET …/new` redirects to the
list. The shared pieces:

- **`#dialog-host`** — an empty `<div>` in `layout/shell` that every create dialog is loaded into.
- **Open/close wiring** in `fragments/htmx`: when a fragment lands in `#dialog-host`, the contained
  `<dialog>` is `showModal()`-ed; any `[data-dialog-close]` button closes its dialog. (The
  `htmx:afterSwap` listener binds to `document`, not `document.body` — that script runs in `<head>`
  where `document.body` is still null.)

**Trigger** (on the list page):

```html
<a
  th:href="@{…/new}"
  th:hx-get="@{…/new}"
  hx-target="#dialog-host"
  hx-swap="innerHTML"
  hx-boost="false"
  >New …</a
>
```

`hx-boost="false"` is **required**: a boosted request carries `HX-Boosted`, which the handler treats
as non-HTMX and answers with a redirect instead of the dialog fragment — so the whole list page
would be injected into `#dialog-host`.

**Template** — `<entity>/new.html` exposes three fragments (no `content`/full page): `createDialog`
(the `<dialog id="create-<entity>-dialog" class="ep-dialog ep-dialog-wide">` shell), `createForm`
(`<form id="create-<entity>-form" th:hx-post hx-target="this" hx-swap="outerHTML" hx-boost="false">`,
re-rendered on error so it swaps itself and the dialog stays open), and `fields` (the inputs + any
inline `<script>`, so the script runs when the dialog is injected). **Name fragments so they don't
collide with tags** — `createForm`/`createDialog`, never `form`/`dialog` (`:: form` is a markup
selector matching every `<form>`).

**Handler** — `newForm` returns the `createDialog` fragment for HTMX and `redirect`s to the list
otherwise; `create` re-renders `createForm` on validation error (dialog stays open with field
errors) and `HX-Redirect`s on success (`303` for non-HTMX). API keys are the exception: success
swaps a `createdReveal` fragment over the form to show the one-time secret inside the dialog.

**Disinherit when the form has an in-page sub-region swap.** `hx-target`/`hx-swap` are **inherited**,
so a form that targets a sub-region leaks that target to descendant controls (e.g. a
`[data-dialog-close]` Cancel). `loadtest/new.html` is the live example: its cascading dropdowns
`hx-get` into `#template-options-section` _inside_ the dialog, so the form carries
`hx-disinherit="hx-target hx-swap"`. Do **not** reach for per-link `hx-target="body"` overrides —
disinherit at the form is the idiomatic fix.

#### Why per-entity `new.html`, not one shared dialog shell

Each entity keeps its own `new.html` (the `createDialog` / `createForm` / `fields` fragments), even
though the **chrome** — the `<dialog>`, header, `<form>`, body/footer with Cancel + submit — is
byte-identical across all of them. We deliberately do **not** factor that chrome into a single
parameterized `fragments/create-dialog.html`. The reasoning:

- The **fields are the substance and are irreducibly per-entity** (a slug + name vs. a multipart
  face-upload vs. the load-test cascade). A shared shell only de-duplicates ~20 lines of trivial,
  stable chrome.
- A shared shell only fits cleanly for the ~7 self-swapping forms. **fonts/assets** post multipart
  and don't self-swap, and **load-test** is bespoke (cascade + `hx-disinherit` + posts to
  `#form-error`) — folding them in needs `multipart`/`selfSwap` flags that turn the shell into
  config soup. So we'd trade "uniform but slightly repeated" for "DRY but two patterns."
- The shell would move the chrome into **model-driven indirection** (the handler supplies
  `dialogId` / `title` / `action` / `fieldsTemplate`), which reads worse than self-contained markup
  and adds wiring to every handler.

So the chosen trade-off is **self-contained, uniform per-entity templates** (every create form has
the same three fragments, readable in one file) over a DRY-but-indirected shared shell. The repeated
chrome is cheap and stable; the indirection is not. Revisit only if the chrome starts changing often
or genuinely diverges.

#### Deep-linkable create dialogs

By default a list's dialog is **transient UI state with no URL** — refresh or Back loses it. Any
list dialog can opt into making its open state **URL-addressable**, so it becomes deep-linkable,
refresh-safe, and closable with the Back button. It is **payable per entity** — adopt it form by form;
a list that hasn't opted in just keeps the plain dialog above. The model is "the list view with a
modifier", so it is a query param on the list URL (`/…/<entities>?create`), **not** a `/new` sub-path,
and it composes with whatever other params that list already carries — preserved, not clobbered (e.g.
on the templates list the open/close round-trip keeps the list's own `?catalog=` filter dropdown — the
filter on the list page, not the separate Catalogs page).

**The query param is per-dialog.** Create forms use `?create`; the file-upload forms (fonts, assets)
use `?upload`. The dialog declares its param via `data-dialog-param` (default `create`), and the
entity-agnostic reconcile/close JS reads that name — so the same wiring serves both, and the server's
`urlWithDialogParam(currentUrl, fallback, param)` builds the matching `HX-Push-Url`.

The wiring below is **entity-agnostic** and now covers **every** create dialog: the seven
self-swapping create forms (templates, themes, API keys, environments, stencils, attributes, code
lists, via `?create`); the two upload forms (fonts, assets, via `?upload`); the **Catalogs** page (via
`?create`) — first converted _to_ this model from its old statically-rendered dialog + inline
`onclick` + OOB-list-swap, its create now HX-Redirecting to the new catalog's `…/<slug>/browse`; and
**load tests** (via `?create`). Load tests are the one case where `pushUrl` is **guarded**: its `/new`
is dual-purpose (dialog-open vs. cascade-dropdown fetch, distinguished by `HX-Trigger-Name`), so the
push fires only on the dialog-open branch — never on a cascade fetch. Only the dialog's _open_ state is
in the URL; the cascade selections are deliberately not. The templates list is the running example.

The split is **the server owns URL writes that ride a request; the client owns the writes that
don't**:

- **Open** — `newForm` adds `pushUrl(urlWithDialogParam(request.htmxCurrentUrl, …, param))` (the
  `param` is `create` or `upload`; `urlWithCreateParam` is the `create` convenience). The merge is a
  single tested Kotlin function that appends the param to the current URL while preserving every
  existing param — never a static string, which would clobber whatever the list already carries (e.g.
  the templates list's `?catalog=` filter).
- **Deep link / refresh / restore** — `list` sets `createOpen` when its param is present and renders
  the `createDialog` fragment (marked `data-create-dialog`, plus `data-dialog-param` for non-`create`
  params) inline on the page, closed.
- **Reconcile** — a persistent, **read-only** function in `fragments/htmx`, bound to
  `DOMContentLoaded` and `htmx:historyRestore`, calls `showModal()`/`close()` to match the URL. It
  **never writes history** — that one-directional flow is what prevents a close↔history loop. Its
  source of truth is **modal-ness (`:modal`), not the `open` attribute**: a back/forward restore
  serializes a **non-modal `<dialog open>`** (the open attribute survives, top-layer/modal state does
  not), so a restored dialog can report `.open === true` while not actually being modal. Reconcile
  therefore re-promotes on `!:modal` (clearing a stale `open` with `removeAttribute` first, since
  `showModal()` throws on an already-open dialog and `.close()` would fire the close→history path).
  This is what makes **forward** re-open the dialog. (Restored `innerHTML` also never re-runs inline
  scripts — another reason the reconcile lives here, once, rather than in the page.)
- **Close** (Cancel / ESC / `closeDialog`) — a capture-phase `close` listener strips `create` with
  `history.replaceState` (safe even when `?create` is the first history entry, unlike
  `history.back()`), preserving other params, and removes a deep-link dialog so a later click-injected
  copy can't collide on the shared id.

To extend this to another entity: add `pushUrl(…)` to its `newForm`, set `createOpen` +
`data-create-dialog` and render the dialog inline on `?create` in its `list`. The reconcile/close
wiring is entity-agnostic (keys off `dialog[data-create-dialog]`) and needs no change.

### Multi-Select Cascading Dropdowns

When multiple `<select>` elements drive a single dynamic section, use `hx-include="closest form"` so the server receives all current form values, and `HX-Trigger-Name` to know which field changed:

```html
<select name="templateId" hx-get="/new" hx-target="#options" hx-include="closest form">
  ...
</select>
<!-- Inside the swapped fragment: -->
<select name="variantId" hx-get="/new" hx-target="#options" hx-include="closest form">
  ...
</select>
<select name="exampleId" hx-get="/new" hx-target="#options" hx-include="closest form">
  ...
</select>
```

```kotlin
val triggerName = request.htmxTriggerName
when (triggerName) {
    "templateId" -> // Template changed: reset all dependent fields
    "variantId"  -> // Variant changed: reload versions, preserve other selections
    "exampleId"  -> // Example changed: preserve everything, update test data
}
```

### Mutually Exclusive Fields

To make two fields mutually exclusive (e.g., explicit version vs environment), place both inside the HTMX-swapped fragment and control visibility server-side with `th:if`:

```html
<!-- Version dropdown: hidden when an environment is selected -->
<div th:if="${#strings.isEmpty(selectedEnvironmentId)}">
  <select name="versionId" hx-get="/new" hx-target="#options" hx-include="closest form">
    <option value="">Use environment instead</option>
    ...
  </select>
</div>

<!-- Environment dropdown: hidden when a version is selected -->
<div th:if="${#strings.isEmpty(selectedVersionId)}">
  <select name="environmentId" hx-get="/new" hx-target="#options" hx-include="closest form">
    <option value="">No environment</option>
    ...
  </select>
</div>
```

The handler clears the opposing field when one is selected:

```kotlin
val selectedVersionId = when (triggerName) {
    "environmentId" -> ""  // environment selected: clear version
    else -> request.param("versionId").orElse("")
}
val selectedEnvironmentId = when (triggerName) {
    "versionId" -> ""  // version selected: clear environment
    else -> request.param("environmentId").orElse("")
}
```

### HTMX Form Submission with Redirect

Using `hx-post` on a form requires special handling for success redirects. A standard 303 redirect is followed by HTMX as an AJAX request, causing the redirected page to be swapped into the target element (duplicating headers, breaking layout).

Use the `HX-Redirect` response header instead — it tells HTMX to perform a full browser navigation:

```kotlin
fun start(request: ServerRequest): ServerResponse {
    try {
        val result = doWork()
        val url = "/items/${result.id}"
        return if (request.isHtmx) {
            ServerResponse.ok().header("HX-Redirect", url).build()
        } else {
            redirect(url)
        }
    } catch (e: Exception) {
        // Return error fragment (must be 200 for HTMX to swap it)
        return request.htmx {
            fragment("mytemplate", "form-error") {
                "error" to (e.message ?: "Something went wrong")
            }
            onNonHtmx {
                ServerResponse.badRequest().render("layout/shell", mapOf("error" to e.message))
            }
        }
    }
}
```

```html
<div id="form-error"><!-- error fragment swapped here --></div>
<form hx-post="/items" hx-target="#form-error" hx-swap="innerHTML">...</form>
```

**Important:** HTMX does not swap content on non-2xx responses by default. Return 200 with error content for inline error display.

### Kotlin Inline Value Classes in Thymeleaf

Kotlin `@JvmInline value class` types (e.g., `VariantId(val value: String)`) are erased at runtime. In Thymeleaf expressions, access the underlying value directly — `.value` does not exist at runtime:

```html
<!-- Correct: variant.id is already a String at runtime -->
<option th:value="${variant.id}" th:selected="${#strings.toString(variant.id) == selectedId}">
  <!-- Wrong: fails with EL1008E "Property 'value' not found on String" -->
</option>

<option th:value="${variant.id.value}"></option>
```

## See Also

- [HTMX Documentation](https://htmx.org/docs/)
- [HTMX Reference](https://htmx.org/reference/)
- [Thymeleaf Fragments](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html#template-layout)
