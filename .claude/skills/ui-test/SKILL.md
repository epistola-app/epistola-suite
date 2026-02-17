---
name: ui-test
description: Create a Playwright UI test for browser-based testing. Use when testing HTMX interactions, form submissions, page navigation, or JavaScript behavior in the browser.
---

Create a Playwright-based UI test for the Epistola Suite.

**Input**: What user interaction or behavior to test.

## Decision Points

Ask the user (if not already specified):
- What page or feature is being tested?
- What user interactions should be verified?
- What HTMX behavior should be tested?
- Are there any prerequisite data setups needed?

## Test Structure

Create in `apps/epistola/src/test/kotlin/app/epistola/suite/ui/<FeatureName>UiTest.kt`.

**Canonical example**: `VariantCardUiTest.kt` — `apps/epistola/src/test/kotlin/app/epistola/suite/ui/VariantCardUiTest.kt`

### Inheritance Chain

`BasePlaywrightTest` → `BaseIntegrationTest`

- `BaseIntegrationTest` provides: Spring Boot `@SpringBootTest(RANDOM_PORT)`, Testcontainers DB, `fixture {}`, `withMediator {}`, `scenario {}`, `createTenant()`
- `BasePlaywrightTest` adds: `page` (Playwright Page), `baseUrl()`, headless Chromium lifecycle

**Tag**: `@Tag("ui")` is inherited from `BasePlaywrightTest` — do NOT add it manually.

### Basic Test Pattern

```kotlin
class FeatureUiTest : BasePlaywrightTest() {

    @Test
    fun `user can create a variant via HTMX form`() {
        // 1. Set up test data
        val (tenant, template) = withMediator {
            val tenant = createTenant("UI Test Tenant")
            val template = CreateDocumentTemplate(
                id = TestIdHelpers.nextTemplateId(),
                tenantId = tenant.id,
                name = "Test Template",
            ).execute()
            tenant to template
        }

        // 2. Navigate to the page
        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/${template.id}")

        // 3. Interact
        page.locator("button:has-text('New Variant')").click()
        page.locator("#slug").fill("english")
        page.locator("form button[type='submit']:has-text('Create Variant')").click()

        // 4. Assert (wait for HTMX swap)
        page.waitForSelector(".variant-card[data-variant-id='english']")
        assertThat(page.locator(".variant-card")).hasCount(2)
    }
}
```

### Data Setup with `withMediator { }`

**Important**: Helper methods must be called **inside** `withMediator { }` at the call site, not defined as separate methods that call `.execute()` independently.

```kotlin
// CORRECT — helper called inside withMediator block
private fun createTestData(): Pair<Tenant, DocumentTemplate> = withMediator {
    val tenant = createTenant("Test")
    val template = CreateDocumentTemplate(
        id = TestIdHelpers.nextTemplateId(),
        tenantId = tenant.id,
        name = "Test",
    ).execute()
    tenant to template
}
```

### TestIdHelpers

Generate unique IDs for test data:

```kotlin
import app.epistola.suite.common.TestIdHelpers

TestIdHelpers.nextTemplateId()     // TemplateId("test-template-1")
TestIdHelpers.nextVariantId()      // VariantId("test-variant-1")
TestIdHelpers.nextEnvironmentId()  // EnvironmentId("test-env-1")
TestIdHelpers.nextAttributeId()    // AttributeId("test-attr-1")
```

## Playwright Patterns

### Locators (prefer user-visible selectors)

```kotlin
page.locator("button:has-text('New Variant')")          // Text content
page.locator("#slug")                                     // ID
page.locator(".variant-card")                             // Class
page.locator("[data-variant-id='some-id']")              // Data attribute
page.locator("select[data-filter-key='language']")       // Attribute
page.locator(".variant-card:not(.variant-card-default)") // Negation
page.locator("form button[type='submit']")               // Nested
```

### Waiting for HTMX

```kotlin
// Wait for element to appear after HTMX swap
page.waitForSelector(".variant-card[data-variant-id='new-id']")

// Wait for element count to change
page.waitForFunction("document.querySelectorAll('.variant-card').length === 2")
```

### Assertions (Playwright built-in)

```kotlin
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

assertThat(page.locator(".element")).hasCount(2)
assertThat(page.locator(".element")).hasText("Expected")
assertThat(page.locator(".element")).isVisible()
assertThat(page.locator(".element")).hasClass(Pattern.compile(".*some-class.*"))
```

### Form Interaction

```kotlin
page.locator("#field-id").fill("value")
page.locator("select#dropdown").selectOption("option-value")
page.locator("input[type='checkbox']").check()
page.locator("form button[type='submit']").click()
```

### Confirm Dialogs

The codebase uses `openConfirmDialog()` (custom HTML dialog), not browser `confirm()`:

```kotlin
// Click the delete button — opens the custom confirm dialog
page.locator("button[title='Delete variant']").first().click()

// Wait for the confirm dialog to appear
page.waitForSelector("#confirm-dialog[open]")

// Click the Delete button inside the dialog
page.locator("#confirm-dialog button.btn-destructive").click()

// Wait for the dialog to close and HTMX swap to complete
page.waitForSelector("#confirm-dialog:not([open])")
```

For the template danger zone delete (which uses native `confirm()`):
```kotlin
page.onDialog { dialog -> dialog.accept() }
page.locator("button.btn-destructive:has-text('Delete Template')").click()
```

## Run Commands

```bash
./gradlew uiTest                              # All UI tests
./gradlew uiTest --tests 'FeatureUiTest'      # Specific test
```

## Checklist

- [ ] Test class extends `BasePlaywrightTest`
- [ ] Data setup via `withMediator { }` with `TestIdHelpers`
- [ ] User interactions via Playwright locators
- [ ] Assertions on visible UI state
- [ ] `./gradlew uiTest`

## Gotchas

- UI tests require **Docker** (Testcontainers for the database)
- Tests use **headless Chromium** (`setHeadless(true)`)
- Each test gets a fresh `page` instance and clean DB state
- Use `TestIdHelpers` for unique IDs — avoids collisions between tests
- Prefer asserting **visible UI state** over checking data in the database
- Keep tests focused on **user interactions**, not implementation details
- `withMediator { }` binds the mediator context — all `.execute()` / `.query()` calls must be inside it
- The `pnpm build` must have been run before UI tests (frontend assets need to exist)
