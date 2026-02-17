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

### 1. Standalone Create (POST + redirect)

Full-page form that submits with POST and redirects on success.

**Reference**: `EnvironmentHandler.newForm` + `EnvironmentHandler.create` + `environments/new.html`

**When**: Creating a new top-level entity (environments, themes, templates, attributes).

**Handler pattern**:
```kotlin
fun create(request: ServerRequest): ServerResponse {
    val tenantId = TenantId.of(request.pathVariable("tenantId"))
    // ... extract form params ...

    fun renderFormWithErrors(errors: Map<String, String>): ServerResponse {
        return ServerResponse.ok().render("layout/shell", mapOf(
            "contentView" to "<entity>/new",
            "tenantId" to tenantId.value,
            "formData" to formData,
            "errors" to errors,
        ))
    }

    try {
        CreateEntity(tenantId = tenantId, ...).execute()
    } catch (e: ValidationException) {
        return renderFormWithErrors(mapOf(e.field to e.message))
    } catch (e: DuplicateIdException) {
        return renderFormWithErrors(mapOf("slug" to "Already exists"))
    }

    return ServerResponse.status(303)
        .header("Location", "/tenants/${tenantId.value}/<entities>")
        .build()
}
```

**Template pattern**: `<form th:action="@{...}" method="post">` — no HTMX attributes on the form.

### 2. Inline HTMX Create

Form embedded in a list/detail page that submits via HTMX and replaces a section.

**Reference**: `templates/detail.html` variant create form + `VariantRouteHandler.createVariant`

**When**: Creating a child entity inline on a parent detail page.

**Handler**: Returns an HTMX fragment (the updated section) instead of redirecting.

**Template pattern**: Form has both `th:action` (fallback) and `th:hx-post` + `hx-target` + `hx-swap="outerHTML"`.

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

### Delete pattern

All list/detail page deletes use `openConfirmDialog()`:
```html
<button type="button"
        class="btn btn-sm btn-icon btn-ghost btn-ghost-destructive"
        data-confirm-title="Delete <Entity>"
        th:attr="data-confirm-message='...', data-confirm-url=@{...}"
        data-confirm-target="#<entity>-rows"
        onclick="openConfirmDialog(this)">
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
- [ ] `./gradlew ktlintFormat`
- [ ] `./gradlew integrationTest`

## Gotchas

- The `request.htmx { }` DSL requires importing `app.epistola.suite.htmx.htmx`
- `redirect()` requires importing `app.epistola.suite.htmx.redirect`
- `.execute()` and `.query()` require importing `app.epistola.suite.mediator.execute` / `.query`
- Templates inside the HTMX DSL `fragment()` use the `"key" to value` syntax (infix `ModelBuilder.to`), not `mapOf()`
- The `renderFormWithErrors()` inner function is a standard pattern for re-rendering forms — keep it local to the `create`/`update` method
- Full-page renders use `ServerResponse.ok().render("layout/shell", mapOf("contentView" to "...", ...))`, fragments just use `request.htmx { fragment(...) }`
