# Refactoring Plan: Readability & Maintainability Improvements

This document tracks the refactoring tasks for improving code readability and maintainability in the Epistola Suite.

## Overview

The refactoring focuses on:

1. Splitting large handlers into smaller, focused units
2. Extracting inline JavaScript to reusable modules
3. Completing exception handling for consistent API responses
4. Extracting duplicate code patterns
5. Standardizing API communication patterns
6. Extracting duplicate Thymeleaf fragments
7. Adding comprehensive logging

## Tasks

### Task 1: Split DocumentTemplateHandler (753 lines)

**Status:** Pending

**File:** `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/DocumentTemplateHandler.kt`

**Changes:**

- Create `VariantRouteHandler.kt` - variant create/delete operations
- Create `VersionRouteHandler.kt` - draft/publish/archive operations
- Create `TemplatePreviewHandler.kt` - preview endpoint
- Keep core template CRUD in `DocumentTemplateHandler.kt`
- Update route registrations in `DocumentTemplateRoutes.kt`

---

### Task 2: Extract Inline JavaScript from Thymeleaf Templates

**Status:** Superseded

Inline scripts were removed wholesale by the strict CSP work (ADR 0010) — behavior lives in
delegated `data-*` hooks in static JS, not per-template modules. The `api-client.js` fetch
wrapper this task proposed was created but never imported, and has been deleted: simple
server interactions belong to native HTMX attributes backed by fragment endpoints (see the
#477 hand-rolled-HTMX conversion), not a shared fetch layer. Only genuinely client-side
call sites (editor callbacks, blob previews) use `fetch()` directly.

---

### Task 3: Complete API Exception Handler

**Status:** Pending

**File:** `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`

**Changes:**

- Add handler for `ThemeNotFoundException` → 404
- Add handler for `ThemeInUseException` → 409
- Add handler for `DataModelValidationException` → 422
- Add handler for `ValidationException` → 400
- Ensure consistent error response structure

---

### Task 4: Extract Duplicate Code Patterns

**Status:** Pending

**4a. UUID Parsing:**

- Create `apps/epistola/src/main/kotlin/app/epistola/suite/common/UuidExtensions.kt`
- Add `String.toUuidOrNull()` extension
- Add `ServerRequest.pathUuid(name)` extension
- Update handlers to use extensions

---

### Task 5: Standardize API Communication Patterns

**Status:** Superseded

Communication standardized the other way around: simple UI interactions became native HTMX
requests against fragment endpoints (form-encoded, see the #477 hand-rolled-HTMX
conversion), and `api-client.js` was deleted rather than adopted. Shared fetch error
handling for the remaining legitimate `fetch()` sites is part of the #477 notice work.

---

### Task 6: Extract Duplicate Thymeleaf Fragments

**Status:** Pending

**New Files:**

- `apps/epistola/src/main/resources/templates/fragments/search.html`
- `apps/epistola/src/main/resources/templates/fragments/form-section.html`

**Changes:**

- Extract search input pattern (used in templates/list, tenants/list, themes/list)
- Extract form section wrapper pattern
- Update list pages to use new fragments

---

### Task 7: Add Comprehensive Logging

**Status:** Pending

**Files:**

- `apps/epistola/src/main/kotlin/app/epistola/suite/mediator/SpringMediator.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`

**Changes:**

- Add logging to `SpringMediator.send()` and `query()` methods
- Add error logging in exception handlers
- Use structured logging patterns with relevant context

---

## Verification

After all tasks complete:

1. `./gradlew test` - all tests pass
2. `./gradlew ktlintCheck` - no style violations
3. Manual test: Navigate through template creation/editing flow
4. Manual test: Verify API error responses for domain exceptions
5. Review logs to confirm new logging statements appear

## Files Modified

### New Kotlin Files

- `apps/epistola/src/main/kotlin/app/epistola/suite/common/UuidExtensions.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/VariantRouteHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/VersionRouteHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/TemplatePreviewHandler.kt`

### Modified Kotlin Files

- `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/DocumentTemplateHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/DocumentTemplateRoutes.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/mediator/SpringMediator.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/ThemeHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/TenantHandler.kt`

### New Template Fragments

- `apps/epistola/src/main/resources/templates/fragments/search.html`

### Modified Templates

- `apps/epistola/src/main/resources/templates/templates/detail.html`
- `apps/epistola/src/main/resources/templates/themes/detail.html`
- `apps/epistola/src/main/resources/templates/templates/list.html`
- `apps/epistola/src/main/resources/templates/tenants/list.html`
- `apps/epistola/src/main/resources/templates/themes/list.html`

### New JavaScript Modules

- (planned as `js/modules/theme-editor.js` / `js/modules/template-detail.js` /
  `js/modules/api-client.js` — none exist under `js/modules/` today. The behavior landed as
  `js/pages/template-detail.js` and `js/theme-editor-boot.js` via the ADR 0010 extraction;
  `api-client.js` was created, never imported, and later deleted. See Tasks 2 and 5.)
