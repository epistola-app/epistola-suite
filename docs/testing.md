# Testing Guide

## Prerequisites

- **Docker** — required for integration tests (Testcontainers)
- **JDK 25** — required for running tests

## Running Tests

```bash
# Unit tests only (no Docker needed)
./gradlew unitTest

# Integration tests (requires Docker)
./gradlew integrationTest

# UI tests (requires Docker + Playwright)
./gradlew uiTest

# All tests
./gradlew test

# Specific test class
./gradlew test --tests "app.epistola.suite.tenants.TenantCommandsTest"

# Coverage report
./gradlew koverHtmlReport
# Report: build/reports/kover/html/index.html
```

## Test Types

| Type        | Tag           | Docker | What it tests                                   |
| ----------- | ------------- | ------ | ----------------------------------------------- |
| Unit        | —             | No     | Utilities, pure functions, validation           |
| Integration | `integration` | Yes    | Business logic, DB operations, commands/queries |
| UI          | `ui`          | Yes    | Browser interactions, HTMX, page rendering      |

## Module Structure

Shared test infrastructure lives in `modules/testing/` and is used by all modules:

```
modules/testing/src/main/kotlin/app/epistola/suite/testing/
├── IntegrationTestBase.kt          # Base class for all integration tests
├── TestApplication.kt              # Minimal Spring Boot app for module tests
├── TestcontainersConfiguration.kt  # PostgreSQL container setup
├── UnloggedTablesTestConfiguration.kt  # Performance: WAL-free tables
├── FakeExecutorTestConfiguration.kt    # Skips real PDF generation
├── Scenario.kt                     # Scenario DSL (preferred)
├── TestFixture.kt                  # TestFixture DSL (legacy)
├── TestIdHelpers.kt                # Unique sequential ID generation
└── TestTenantCounter.kt            # Namespace-based tenant slugs
```

## Test Hierarchy

There are three test contexts, each booting a different Spring application:

```
IntegrationTestBase (modules/testing)
│  @SpringBootTest(classes = [TestApplication])
│  Provides: withMediator(), fixture(), scenario(), createTenant()
│
├── Module integration tests (epistola-core, epistola-catalog, etc.)
│     Extend IntegrationTestBase directly
│     Boot TestApplication (minimal, no web layer)
│
├── BaseIntegrationTest (apps/epistola)
│     @SpringBootTest(classes = [EpistolaSuiteApplication])  ← overrides
│     Adds: TestSecurityContextConfiguration, per-class DB cleanup
│     │
│     └── BasePlaywrightTest (apps/epistola)
│           @SpringBootTest(..., webEnvironment = RANDOM_PORT)
│           Adds: Playwright browser lifecycle, baseUrl()
```

**Key rule:** The closest `@SpringBootTest` annotation wins. `IntegrationTestBase` defaults to `TestApplication`. App tests override it with `EpistolaSuiteApplication`.

## Writing Tests

### Unit Tests

No Spring context, no Docker. Test pure logic:

```kotlin
class CatalogKeyTest {
    @Test
    fun `valid slug is accepted`() {
        val key = CatalogKey.of("my-catalog")
        assertEquals("my-catalog", key.value)
    }

    @Test
    fun `too short slug is rejected`() {
        assertThrows<IllegalArgumentException> {
            CatalogKey.of("ab")
        }
    }
}
```

### Module Integration Tests

Extend `IntegrationTestBase`. Boots `TestApplication` (minimal context):

```kotlin
class ImportTemplatesTest : IntegrationTestBase() {
    @Test
    fun `import creates template`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val results = ImportTemplates(
                tenantId = TenantId(tenant.id),
                templates = listOf(/* ... */),
            ).execute()

            assertThat(results).hasSize(1)
            assertThat(results[0].status).isEqualTo(ImportStatus.CREATED)
        }
    }
}
```

### App Integration Tests

Extend `BaseIntegrationTest`. Boots the full app with security:

```kotlin
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@AutoConfigureTestRestTemplate
class TenantRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET homepage returns tenant list`() = fixture {
        given { tenant("Acme Corp") }
        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Acme Corp")
        }
    }
}
```

### UI Tests (Playwright)

Extend `BasePlaywrightTest`. Full app with a real browser:

```kotlin
class VariantCardUiTest : BasePlaywrightTest() {
    @Test
    fun `creating a variant adds a card`() {
        val (tenant, template) = withMediator { createTenantAndTemplate() }

        gotoAndReady("/tenants/${tenant.id}/templates/default/${template.id}")

        val dialog = page.openDialogByTrigger(
            page.locator("button:has-text('New Variant')"),
            "#create-variant-dialog",
        )
        dialog.locator("#slug").fill("new-variant")
        dialog.locator("button[type='submit']").click()
        page.htmxSettle()

        assertThat(page.locator(".variant-card[data-variant-id='new-variant']")).isVisible()
    }
}
```

#### Required patterns (enforced by `UiTestHygieneTest`)

UI tests are flaky-prone; these rules make the deterministic thing the
default. They are a hard gate — `UiTestHygieneTest` (a fast unit test) fails
the build on any violation. See issue #418 for the history.

- **Navigate with `gotoAndReady(path)`** — never bare `page.navigate(...)`.
  It waits for `DOMCONTENTLOADED` (not `NETWORKIDLE`, which an
  `hx-trigger="load"` keeps busy).
- **Await HTMX with `page.htmxSettle()`** after any action that triggers an
  `hx-*` swap, _before any non-retrying read_ (`innerText`, `count()`,
  `getAttribute`). Web-first assertions already retry, so prefer asserting the
  expected post-swap state directly when you can.
- **Open dialogs/popovers with `page.openDialogByTrigger(trigger, selector)`**
  — never a blind `waitForSelector("…[open]")`. It waits for deferred
  handler-binding scripts (`load` state) and for the dialog to be open **and**
  non-zero (defeating the async zero-height popover).
- **Assert web-first** with Playwright's `assertThat(locator)…`. Never the
  query-time `:visible` CSS pseudo — select shown elements _structurally_
  (e.g. `.card:not([style*='display: none'])`).
- **No `waitForTimeout`, no forensic `System.err.println` dumps.** A hung
  selector is captured in the persisted Playwright trace on failure.
- **Time-dependent UI** (auto-hide, debounce): don't race a wall clock.
  Make the timing test-injectable (see the editor's `?leaderTiming=` /
  `data-leader-timing` seam) — a "sticky" value makes a transient state
  effectively permanent (race-free assertion), a "fast" value makes the
  end-state deterministic — then assert the end state web-first.
- **Server-contract-only flows belong at the handler level**, not in a
  browser. Add a `*HandlerHtmxTest` (see `StencilHandlerHtmxTest`,
  `CodeListHandlerHtmxTest`): `@SpringBootTest(RANDOM_PORT)` +
  `@AutoConfigureTestRestTemplate`, assert status/headers/body directly.
  Deterministic, fast, no Playwright.

The shared helpers live in `PlaywrightHtmxSupport` (app test source set);
`BasePlaywrightTest` installs the HTMX activity bookkeeper per page.

## Test DSLs

### withMediator / withAuthentication

Binds the mediator and security context so you can call `.execute()` and `.query()`:

```kotlin
withMediator {
    val tenant = CreateTenant(id = TenantKey.of("test"), name = "Test").execute()
    val tenants = ListTenants().query()
}
```

`withAuthentication` is an alias for `withMediator` — they do the same thing.

### TestFixture DSL

Given-When-Then pattern with automatic cleanup:

```kotlin
fixture {
    given {
        val tenant = tenant("Acme Corp")
        template(tenant, "Invoice")
        noTenants()  // delete all tenants
    }
    whenever {
        createTenant("New Corp")
    }
    then {
        val tenant = result<Tenant>()
        assertThat(tenant.name).isEqualTo("New Corp")
    }
}
```

### Scenario DSL (preferred for complex tests)

Type-safe setup with automatic LIFO cleanup and data passing between phases:

```kotlin
scenario {
    given {
        val tenant = tenant("Test")
        val template = template(tenant.id, "Invoice")
        val variant = variant(tenant.id, template.id)
        val version = version(tenant.id, template.id, variant.id, templateModel)
        DocumentSetup(tenant, template, variant, version)
    }.whenever { setup ->
        GenerateDocument(setup.tenant.id, /* ... */).execute()
    }.then { setup, result ->
        assertThat(result.id).isNotNull()
    }
}
```

## Test Helpers

### createTenant()

Creates a tenant with a unique namespace-scoped slug:

```kotlin
val tenant = createTenant("My Tenant")
// slug: "tenantcommandstest-1" (derived from test class name)
```

### TestIdHelpers

Generates unique sequential IDs to avoid collisions across parallel tests:

```kotlin
val templateId = TestIdHelpers.nextTemplateId()   // tpl-001, tpl-002, ...
val variantId = TestIdHelpers.nextVariantId()     // var-001, var-002, ...
val environmentId = TestIdHelpers.nextEnvironmentId()
```

### Scheduled work: `scheduling` (deterministic substrate)

Integration tests never run the production wall-clock scheduler loop — it
cannot see the per-test `ScopedValue` clock and its autonomous ticks would race
parallel test classes on installation-wide state. `IntegrationTestBase` selects
the deterministic substrate (`epistola.cluster.scheduling-substrate=test`) and
exposes a driver that runs due cluster timers and scheduled tasks explicitly,
synchronously, on the test thread:

```kotlin
// "a day passes and due scheduled work runs"
scheduling.advanceTimeBy(Duration.ofHours(25))

// run what is already due without moving the clock
scheduling.runDue()

// move time without firing anything
testClock.advanceBy(Duration.ofMinutes(5))
```

Do not add `Thread.sleep`/Awaitility waits for scheduler ticks — drive them.
Document generation jobs need no tick at all: enqueueing publishes an event
that nudges the `JobPoller` drain immediately. See
[`docs/timers.md`](timers.md#scheduling-substrate-trigger-vs-engine).

## Performance Optimizations

- **Testcontainers reuse** — containers persist across test runs (`withReuse(true)`)
- **UNLOGGED tables** — `UnloggedTablesTestConfiguration` converts all tables to UNLOGGED after migrations, eliminating WAL writes
- **tmpfs** — PostgreSQL data directory is on tmpfs (in-memory)
- **Fake PDF generation** — `FakeDocumentGenerationExecutor` creates minimal valid PDFs instantly
- **Parallel execution** — test classes run concurrently, methods within a class run sequentially
- **Namespace isolation** — each test class uses a unique slug prefix, no cross-class interference

## Adding Tests to a New Module

1. Add `testImplementation(project(":modules:testing"))` to your `build.gradle.kts`
2. Extend `IntegrationTestBase` for integration tests
3. Tests automatically get: Testcontainers, mediator context, fixture DSL, test user

```kotlin
// That's it — no other setup needed
class MyFeatureTest : IntegrationTestBase() {
    @Test
    fun `my feature works`() {
        val tenant = createTenant("Test")
        withMediator {
            // test your feature
        }
    }
}
```

## Troubleshooting

### Docker Not Running

```
Could not find a valid Docker environment
```

Start Docker Desktop or the Docker daemon.

### Tests Hanging

1. Check Docker has enough resources
2. Look for zombie containers: `docker ps -a`
3. Clean up: `docker system prune`

### Two @SpringBootApplication Conflict

If you see `Found multiple @SpringBootConfiguration` errors, ensure your test specifies `classes = [...]` in its `@SpringBootTest` annotation. The testing module's `TestApplication` and the app's `EpistolaSuiteApplication` both exist on the test classpath.
