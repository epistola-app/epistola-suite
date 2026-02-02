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

**File:** `apps/epistola/src/main/kotlin/app/epistola/suite/templates/DocumentTemplateHandler.kt`

**Changes:**
- Create `VariantRouteHandler.kt` - variant create/delete operations
- Create `VersionRouteHandler.kt` - draft/publish/archive operations
- Create `TemplatePreviewHandler.kt` - preview endpoint
- Keep core template CRUD in `DocumentTemplateHandler.kt`
- Update route registrations in `DocumentTemplateRoutes.kt`

---

### Task 2: Extract Inline JavaScript from Thymeleaf Templates

**Status:** Pending

**Files:**
- `apps/epistola/src/main/resources/templates/themes/detail.html` (lines 128-275)
- `apps/epistola/src/main/resources/templates/templates/detail.html` (lines 172-288)

**Changes:**
- Create `/static/js/modules/api-client.js` - shared fetch logic with CSRF handling
- Create `/static/js/modules/theme-editor.js` - theme CRUD operations
- Create `/static/js/modules/template-detail.js` - template configuration
- Update templates to import modules

---

### Task 3: Complete API Exception Handler

**Status:** Pending

**File:** `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`

**Changes:**
- Add handler for `ThemeNotFoundException` → 404
- Add handler for `ThemeInUseException` → 409
- Add handler for `LastThemeException` → 400
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

**Status:** Pending

**Files:**
- `templates/editor.html`
- `templates/detail.html`
- `themes/detail.html`

**Changes:**
- Standardize content-type to `application/json`
- Extract shared fetch error handling to `api-client.js`

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
- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/DocumentTemplateHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/templates/DocumentTemplateRoutes.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/api/v1/ApiExceptionHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/mediator/SpringMediator.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/themes/ThemeHandler.kt`
- `apps/epistola/src/main/kotlin/app/epistola/suite/tenants/TenantHandler.kt`

### New Template Fragments
- `apps/epistola/src/main/resources/templates/fragments/search.html`

### Modified Templates
- `apps/epistola/src/main/resources/templates/templates/detail.html`
- `apps/epistola/src/main/resources/templates/themes/detail.html`
- `apps/epistola/src/main/resources/templates/templates/list.html`
- `apps/epistola/src/main/resources/templates/tenants/list.html`
- `apps/epistola/src/main/resources/templates/themes/list.html`

### New JavaScript Modules
- `apps/epistola/src/main/resources/static/js/modules/api-client.js`
- `apps/epistola/src/main/resources/static/js/modules/theme-editor.js`
- `apps/epistola/src/main/resources/static/js/modules/template-detail.js`
