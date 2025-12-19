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

The `HtmxRequest.kt` file provides extension properties on `ServerRequest`:

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

### Available Extensions

| Property | Header | Description |
|----------|--------|-------------|
| `isHtmx` | `HX-Request` | True if request was made by HTMX |
| `htmxTrigger` | `HX-Trigger` | ID of the element that triggered the request |
| `htmxTriggerName` | `HX-Trigger-Name` | Name of the element that triggered the request |
| `htmxTarget` | `HX-Target` | ID of the target element |
| `htmxCurrentUrl` | `HX-Current-URL` | Current URL of the browser |
| `htmxBoosted` | `HX-Boosted` | True if request is via hx-boost |
| `htmxHistoryRestoreRequest` | `HX-History-Restore-Request` | True if restoring history |
| `htmxPrompt` | `HX-Prompt` | User response to hx-prompt |

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
<tbody id="table-rows" hx-swap-oob="true">...</tbody>

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

| Value | Description |
|-------|-------------|
| `INNER_HTML` | Replace inner HTML (default) |
| `OUTER_HTML` | Replace entire element |
| `BEFORE_BEGIN` | Insert before the element |
| `AFTER_BEGIN` | Insert as first child |
| `BEFORE_END` | Insert as last child |
| `AFTER_END` | Insert after the element |
| `DELETE` | Delete the element |
| `NONE` | Don't swap (useful with OOB) |

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
<head>...</head>
<body>
    <main>
        <h1>Items</h1>

        <table>
            <thead>...</thead>
            <tbody id="table-body" th:fragment="table-body">
                <tr th:each="item : ${items}">
                    <td th:text="${item.id}"></td>
                    <td th:text="${item.name}"></td>
                    <td>
                        <button hx-delete="/items/${item.id}"
                                hx-target="closest tr"
                                hx-swap="delete">
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
<div th:fragment="success"
     id="toast"
     class="toast success"
     hx-swap-oob="true">
    <span th:text="${message}"></span>
</div>
```

## File Structure

```
app/epistola/suite/htmx/
├── HtmxRequest.kt         # ServerRequest extensions
├── HtmxRender.kt          # render(), renderTemplate(), htmx {} entry point
├── HtmxDsl.kt             # DSL builders (HtmxResponseBuilder, ModelBuilder)
├── HtmxSwap.kt            # HxSwap enum
└── HtmxFragmentsView.kt   # Multi-fragment view rendering
```

## Best Practices

1. **Use simple `render()` for basic cases** - Don't use the DSL when you just need a single fragment with redirect fallback.

2. **Keep fragments small** - Design fragments as reusable, self-contained pieces of UI.

3. **Always provide `onNonHtmx`** - Ensure your app works without JavaScript (progressive enhancement).

4. **Use OOB sparingly** - Out-of-band updates are powerful but can make debugging harder. Use them for notifications, counters, and status updates.

5. **Trigger events for coordination** - Use `trigger()` to notify other parts of the page about changes, rather than updating everything server-side.

## See Also

- [HTMX Documentation](https://htmx.org/docs/)
- [HTMX Reference](https://htmx.org/reference/)
- [Thymeleaf Fragments](https://www.thymeleaf.org/doc/tutorials/3.1/usingthymeleaf.html#template-layout)
