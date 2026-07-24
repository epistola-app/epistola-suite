<!--
  SPDX-FileCopyrightText: Epistola Nederland B.V.

  SPDX-License-Identifier: AGPL-3.0-only
-->

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

## Architecture-enforcement tests

Documented conventions are enforced by plain unit tests (no Docker, run in `unitTest`)
in `apps/epistola/src/test/kotlin/app/epistola/suite/architecture/`. They run against the
app's full runtime classpath and the whole repository's sources, so feature modules are
covered too:

| Test                                                 | Enforces                                                                                                                                                                                                           |
| ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `MediatorWiringTest`                                 | Every command/query has exactly one handler; handlers are `@Component`; every command/query implements an `Authorized` subtype                                                                                     |
| `ApplicationClockUsageTest`                          | No direct JVM `now()` calls in production code — use `EpistolaClock` (see [`docs/clock.md`](clock.md)); with-clock overloads and database `NOW()` remain fine                                                      |
| `DomainBoundaryTest`                                 | Mediator handlers are never imported outside their own package — cross-domain calls dispatch the command/query through the mediator                                                                                |
| `NoHardcodedSecretsTest`                             | Config hygiene: sensitive `application*.yaml`/`.properties` keys must use `${ENV}`/Secrets, not literals (dev/local files allowlisted). General secret detection is gitleaks' job (pre-commit + CI), not this test |
| `UiRestApiSeparationTest`                            | UI templates and static JS never call REST API endpoints                                                                                                                                                           |
| `UiTestHygieneTest`                                  | UI tests use the deterministic Playwright helpers (see [UI tests](#ui-tests-playwright))                                                                                                                           |
| `BundledCatalogFingerprintTest` (in `epistola-core`) | Bundled demo/system catalog fingerprints match their content                                                                                                                                                       |

Handler scanning is metadata-based and deliberately ignores `@Conditional` annotations:
a handler gated on a property (e.g. `RefreshEntitlementsHandler` on
`epistola.support.enabled`) still counts as wired. Genuine exceptions to the clock rule
go in the test's `allowedFiles` set with a documented reason.

### Secret scanning (gitleaks)

General credential detection is handled by [gitleaks](https://github.com/gitleaks/gitleaks)
(pinned in `.mise.toml`, config + allowlist in `.gitleaks.toml`), not by a hand-rolled
check:

- **Local, pre-commit:** the `.husky/pre-commit` hook runs `gitleaks git --staged` —
  it scans only the staged diff (~0.4s) and blocks the commit on a finding, so a secret
  never enters local history. Requires `mise install` (the hook no-ops with a warning if
  `mise` is absent). Bypassable in a pinch with `git commit --no-verify`.
- **CI, hard gate:** the _Secret Scan_ workflow (`.github/workflows/gitleaks.yml`) runs
  `gitleaks git` over full history on every PR and push to `main`.

`NoHardcodedSecretsTest` is complementary, not a substitute: gitleaks is entropy/format
based and won't flag a low-entropy `password: admin` in a prod config — the test catches
that policy violation.

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
├── Module integration tests (epistola-core, epistola-mcp, etc.)
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
Document generation is **opt-in** in tests: nothing drains automatically, so a
test only renders a document when it explicitly calls
`drainGenerationJobs(tenant.id)` (synchronous, tenant-scoped) and then asserts on
the result. Tests that only need the created request (PENDING status, metadata,
validation) should not call it. See
[`docs/timers.md`](timers.md#scheduling-substrate-trigger-vs-engine).

## Test-run metrics (cross-cutting)

Test performance is captured automatically on **every** run by a cross-cutting
harness in `modules/testing` (`app.epistola.suite.testing.metrics`) — there is no
per-test instrumentation. It is wired through three seams:

- **`TestTimingListener`** — a JUnit Platform `TestExecutionListener`
  auto-registered via `META-INF/services`, so it observes every test in every
  module that depends on `modules:testing`. It times each test class and, at the
  end of the run, prints a compact summary and writes a machine-readable JSON
  report.
- **`ContextBootCounter`** — a Spring `ApplicationContextInitializer` registered
  via `META-INF/spring.factories`. It runs once per fresh `ApplicationContext`
  boot, so its count equals the **test-context cache misses** — the cost driver
  behind context fragmentation (a class that adds `@TestPropertySource`/`@Import`/
  `@MockkBean` gets its own context).
- **`MetricsRecordingMediator`** — a `@Primary` `Mediator` decorator (imported by
  `IntegrationTestBase`) that times every command/query. Because tests bind it via
  `withMediator`, nested `.execute()`/`.query()` calls flow through it too, so a
  command's time includes its nested work (e.g. `CreateTenant` includes the
  `InstallSystemCatalog`/font import it triggers), and the tenant-bootstrap count
  falls out of the `CreateTenant` count.

Each test task writes `build/test-metrics/<module>-<task>.json`:

```json
{ "label": "...", "wallMillis": 0, "classCount": 0, "contextBoots": 0,
  "tenantsCreated": 0, "classes": [...], "commands": [...], "queries": [...] }
```

CI uploads these as the `test-run-metrics` artifact (30-day retention), so suite
performance is monitorable over time and regressions (like a series that silently
adds minutes) are caught with evidence rather than eyeballing. The console summary
also prints the slowest classes and costliest commands at the end of each run.

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
