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

if (form.hasErrors()) {
    return ServerResponse.ok().page("environments/new") {
        "pageTitle" to "New Environment - Epistola"
        "tenantId" to tenantId.value
        "formData" to form.formData
        "errors" to form.errors
    }
}

val environmentId = form.getEnvironmentId("slug")!!
val name = form["name"]

// Execute command and automatically map exceptions to form errors
val result = form.executeOrFormError {
    CreateEnvironment(id = environmentId, tenantId = tenantId, name = name).execute()
}

if (result.hasErrors()) {
    return ServerResponse.ok().page("environments/new") {
        "pageTitle" to "New Environment - Epistola"
        "tenantId" to tenantId.value
        "formData" to result.formData
        "errors" to result.errors
    }
}
```

**Supported validators:** `required()`, `pattern(regex)`, `minLength(n)`, `maxLength(n)`, `min(n)`, `max(n)`

**Domain ID validators:** `asEnvironmentId()`, `asTemplateId()`, `asVariantId()`, `asVersionId()`, `asTenantId()`

**Type coercion:** `asInt()`, and typed accessors like `form.getInt("count")`, `form.getEnvironmentId("envId")`

**Exception mapping:** Automatically converts `ValidationException` and `DuplicateIdException` to field errors.

### Idea B: Unified Page + Fragment DSL

Eliminate `if (!request.isHtmx)` branches by using `onNonHtmx { page(...) }` inside the htmx DSL:

**Before (separate conditional):**

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    val tenantId = TenantId.of(request.pathVariable("tenantId"))

    if (!request.isHtmx || request.htmxBoosted) {
        val templates = ListDocumentTemplates(tenantId = tenantId).query()
        return ServerResponse.ok().page("loadtest/new") {
            "pageTitle" to "Start Load Test"
            "templates" to templates
        }
    }

    // HTMX fragment logic...
    return request.htmx {
        fragment("loadtest/new", "template-options") { ... }
    }
}
```

**After (unified DSL):**

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    val tenantId = TenantId.of(request.pathVariable("tenantId"))

    return request.htmx {
        onNonHtmx {
            page("loadtest/new") {
                "pageTitle" to "Start Load Test"
                "templates" to ListDocumentTemplates(tenantId = tenantId).query()
            }
        }
        fragment("loadtest/new", "template-options") { ... }
    }
}
```

The `onNonHtmx { }` block now supports both `page()` and `redirect()` calls, unifying request handling in a single DSL scope.

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

### Serving Full Pages and Fragments from One Endpoint

When `hx-boost="true"` is on `<body>`, all link navigation sends `HX-Request: true` with `HX-Boosted: true`. If your endpoint also handles HTMX fragment requests (e.g., dynamic form updates), you must distinguish boosted navigation from in-page fragment updates:

```kotlin
fun newForm(request: ServerRequest): ServerResponse {
    if (!request.isHtmx || request.htmxBoosted) {
        // Full page: navigation or boosted link click
        return ServerResponse.ok().render("layout/shell", mapOf(...))
    }

    // HTMX fragment: in-page update (e.g., select changed)
    return request.htmx {
        fragment("mytemplate", "my-fragment") { ... }
    }
}
```

Without the `htmxBoosted` check, boosted navigation receives a fragment instead of the full page.

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
