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

# Coverage report (coverage is measured only when a Kover task is asked for;
# plain `test` runs uninstrumented)
./gradlew test koverHtmlReport
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
| `SystemCatalogInstallUsageTest` (in `guards`)        | Only the tests' `SystemCatalogTemplate` creates a tenant without its system catalog (see [the template](#test-tenants-and-the-system-catalog-template))                                                            |

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

#### Catalog invariants after every class

`IntegrationTestBase` also checks, once after each test class that created a tenant, that the
commands it ran left that class's tenants' catalogs consistent: `CatalogInvariants` in
`modules/testing` is one SQL statement whose every `SELECT` returns the rows breaking one rule the
database cannot enforce by itself (a release pins only releases the tenant has, what a release names
resolves through its pins, a draft names nothing it cannot resolve, catalogs never use each other,
a released stencil never contains itself,
a draft row's identity is registered at its address, an image's bytes are stored, …). So every integration test also tests the sequence of
commands it ran against those rules, at the cost of one query per class (`invariantChecks` in the
test-run metrics). A violation fails the class and names the test that created the tenant.

- `CatalogInvariantsTest` proves each rule finds the corruption it names; a rule added there needs
  a corruption that trips it, and one the database already refuses is left out.
- A test that corrupts its catalogs on purpose (to see a guard refuse or report a state only the 1.x
  upgrade or an imported release produces) opts out with `@SkipCatalogInvariants("why")`; on a
  test method only the tenants that test creates are left out, on a class all of them.
- `-PtestInvariants=report` writes violations to `build/invariant-violations/` instead of failing,
  to survey a suite after adding a rule; `off` skips the check.

#### Random command sequences against the invariants

`CatalogModelTest` (Kotest's property library, inside a JUnit test) runs random sequences of real
catalog commands in a fresh tenant: catalogs, stencils, themes and templates created and deleted,
content using stencils of other catalogs, stencils containing stencils (themselves among the picks), themes set, releases cut and deleted, dependencies
declared, pinned and undeclared, resources moved. After every step the command must not have
crashed (a null, cast or index error, or a raw SQL error: a refusal is any other exception) and the
invariants above must hold. A step picks what it acts on from what exists then, so steps meet each
other's work; one pick in eight names nothing, to keep refusals exercised. A failing sequence is
shrunk and printed with the seed and what each step did.

- It is opt-in, tagged `model` and left out of `test` and `integrationTest`:
  `./gradlew :modules:epistola-core:modelTest` runs 100 sequences (about 1.5 minutes). CI runs
  1,000 nightly (`model-test.yml`; run it by hand with a count or a seed from the Actions tab).
  `-PmodelRuns=1000` searches harder (about 15 minutes); `-PmodelSeed=S` replays a failure. Without
  a seed every run searches somewhere new, so rerun it with `modelTest --rerun`, or Gradle replays
  the cached result.
- The console line `Catalog model:` counts each step's outcomes and the first message of each kind
  of refusal. A step that is never `done`, or always refused the same way, tests nothing: adjust its
  picks.
- A new command touching catalogs belongs in it as a step.

#### Random 1.x installations through the upgrade

`OneXUpgradeModelTest` (same task, same properties) generates 1.x installations: catalogs with
releases, themes, stencils and templates, each stencil and variant with a version history, data
contract versions, stencil instances (bare, an exact copy of the pinned version, or an edited one) of
the template's own catalog, another or one the tenant lacks, and environment activations. Each is
seeded on the last 1.x schema in a database of its own and put through the preflight and the 2.0
chain. The preflight must refuse exactly the installations whose kept content uses a missing
catalog or makes catalogs use each other; any other must upgrade with the catalog invariants holding,
each next draft holding every working copy, and "Published in 1.x" (or the next draft, when that
was dropped as the same) what each last published. Content carries markers (`T cat-a/tpl-0/default
v2`), so the checks read which version a draft holds.

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

        gotoAndReady("/tenants/${tenant.id}/catalogs/default/templates/${template.id}")

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

Its system catalog is **copied from a template tenant**, not installed. See the next section.

### Test tenants and the system catalog template

Every tenant gets the bundled system catalog, and the tests create a few thousand tenants. Installing
it for each one (about 17 resources through the whole import path) was most of the integration
tests' time on CI, so a test tenant gets a copy instead:

1. The first time a test database needs it, `SystemCatalogTemplate` (`modules/testing`) creates the
   tenant `system-catalog-template` with a plain `CreateTenant`, which installs the system catalog
   for real, from the same bundled catalog every tenant gets.
2. A test tenant is created with `CreateTenant(..., installSystemCatalog = false)` and then gets a
   copy of the template's system-catalog rows (about 180, in nine tables) under its own tenant key,
   with triggers off for the copy (`session_replication_role = replica`).

`createTenant(name)`, `createTenant(id, name)`, `fixture { given { tenant(...) } }` and
`scenario { given { tenant(...) } }` all copy by default. A test about installing the system catalog
asks for the real install:

```kotlin
val tenant = createTenant("Sys1", SystemCatalogSetup.INSTALL)
```

A tenant created any other way (`TestNamespaces.createTenant(...).execute()`, `CreateTenant`
directly, over HTTP, demo mode) installs it as production does.

What holds it honest:

- `SystemCatalogTemplateTest` — a copied tenant holds what an installed one holds, in **every**
  tenant-scoped table (compared without tenant key, ids, timestamps and the release key drawn at
  random per install). When installing the system catalog writes a new table or writes differently,
  it fails; add the table to `SystemCatalogTemplate.TABLES` or fix the copy.
- `SystemCatalogTemplateEventHandlersTest` — no `EventHandler` reacts to a command the copy skips.
- `SystemCatalogInstallUsageTest` (guards) — only `SystemCatalogTemplate` passes
  `installSystemCatalog = false`; production never creates a tenant without its system catalog.

#### Known limits, and what to suspect when a test misbehaves

If a test behaves differently from production around a tenant's system catalog, the first check is
to create its tenant with `SystemCatalogSetup.INSTALL`. If the problem goes away, it is one of these:

| Limit                                                                                                                              | What you would see                                                                                                                                              | Why                                                                                                                                                                                                      | What to do                                                                                                                                                   |
| ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Ids are shared across copied tenants.** The copy keeps the template's catalog, version and resource ids under each tenant's key. | A query returns rows of another tenant, or `.one()` finds several rows, only in tests.                                                                          | Every key of these tables includes `tenant_key`, so the database allows it; production ids are unique per tenant, so a query that forgets `tenant_key` is wrong there too but never shows it.            | Fix the query to filter on `tenant_key`. It is a real bug the copy exposed, not a test artefact.                                                             |
| **The install commands are not sent for a copied tenant.**                                                                         | Something that reacts to installing the system catalog (an audit entry, an event-log row, a metric, a cache warmed by the install) is missing for test tenants. | The copy writes the rows, it sends no commands. `SystemCatalogTemplateEventHandlersTest` covers `EventHandler`s; cross-cutting `CommandListener`s (audit), the event log and metrics see nothing either. | Use `SystemCatalogSetup.INSTALL` in that test. If the reaction belongs to every tenant, it belongs in the rows and `SystemCatalogTemplateTest` will show it. |
| **Most tests no longer run the install path.**                                                                                     | A regression in installing the system catalog passes everywhere except the install tests.                                                                       | Only `INSTALL` tenants, the install, upgrade and model tests, and the template itself run it.                                                                                                            | Keep tests about installing on `INSTALL` (`InstallSystemCatalogTest`, `InstalledReleaseTest`, `SystemFontsIntegrationTest`).                                 |
| **The copy switches triggers off.**                                                                                                | `permission denied to set parameter "session_replication_role"`.                                                                                                | `session_replication_role` needs a superuser; the test container's user is one.                                                                                                                          | If the tests move to a non-superuser role, copy with the triggers on, in their dependency order.                                                             |
| **The template is a tenant in the test database.**                                                                                 | A query over all tenants finds `system-catalog-template`.                                                                                                       | It lives beside the test tenants, once per database.                                                                                                                                                     | Filter by the test's own tenants (they are named after the test class).                                                                                      |

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

## One Spring context per configuration

A Spring test context boots once per distinct configuration and is cached for the JVM, and every
extra configuration is a full boot (5 s idle, 20 to 40 s on a loaded machine or a saturated CI
runner) plus its own database. Context boots are the largest share of integration-test time, so
the rule is: **a test class gets its own configuration only when it tests that configuration.**

What makes a configuration distinct, and what to do instead:

- `@TestPropertySource` / `@SpringBootTest(properties = …)` with values that **equal the defaults**
  (the cluster lease and retry knobs were 30 000 ms, which is what production uses): drop them.
- Properties that every test can live with (`allow-http` and `outbound.allow-private-networks`
  for loopback fakes, `file-roots` naming `java.io.tmpdir` for `@TempDir` catalogs, the Prometheus
  endpoint): put them in the module's `application-test.yml` and drop the per-class source.
- A per-class fake server registered through `@DynamicPropertySource`: one shared server per JVM
  (`SharedFakeExchange`) behind a base class that owns the single `@DynamicPropertySource`, holds a
  `@ResourceLock` so the classes that script the fake never overlap, resets it and purges the
  installation-wide rows before every test (`ExchangeIntegrationTestBase`,
  `ExchangeDiscoveryIntegrationTestBase`, the app's `ExchangeHandlerTestBase`). The support
  service works the same way (`SharedFakeSupport`, the app's `AutoRegisteringSupportTestBase` and
  `ManualSupportTestBase`), except that it resets once per class, as a server of its own did,
  because those classes build their registration up from test to test. Before these shared it, the
  apps test JVM booted a context per support class and ran out of heap.
- A nested `@TestConfiguration` that several classes need: one shared configuration class imported
  by all of them (`ClusterRecordingHandlersConfiguration`). Whether they can then share the context
  is a separate question, answered by the next point.
- A class that **mutates installation-wide rows** (reclaims cluster leases, deletes another node's
  tasks, disables scheduled tasks) needs a database of its own, which only a private context gives
  it. Say so explicitly rather than through a property that happens to differ:

  ```kotlin
  // Reclaims leases and disables tasks installation-wide: needs a database of its own.
  @TestPropertySource(properties = ["epistola.test.private-context=cluster-timer-registry"])
  ```

  The property is read by nothing; its value names the context so two classes never share one by
  accident.

The `contextBoots` figure in `build/test-metrics/<module>-<task>.json` is the number to watch; a
class that adds one should be able to say which configuration it is testing.

### A context closes after its last class

Spring's cache keeps a context until the JVM exits, because it cannot know whether a later class
will ask for it again. A test run can know: before the first test, `ContextUsageListener` works out
each class's context the way Spring's cache keys it, and counts the classes per context. After each
class, `ReleaseContextWhenDoneExtension` (registered first on `IntegrationTestBase`, so it runs after
every other `afterAll`, the invariants check included) counts it off, and closes the context once the
last class using it is done. `ContextGroupingClassOrderer` (`junit.jupiter.testclass.order.default`)
starts each context's classes together, so a context is alive only while its own classes run.

The test JVM's heap has to hold the contexts alive at once, not every context the run boots:
`contextsAliveMax` in the metrics report is that figure. On the apps test JVM it went from 38 to 15.
At 1g the run ran out of heap after 757 of 1368 tests before, and passed whole after. A context
booted twice (`contextBoots` rising with no new configuration) means a class outside the count used
it after it closed.

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
  "tenantsCreated": 0, "invariantChecks": 0, "invariantCheckMillisTotal": 0,
  "classes": [...], "commands": [...], "queries": [...] }
```

CI uploads these as the `test-run-metrics` artifact (30-day retention), so suite
performance is monitorable over time and regressions (like a series that silently
adds minutes) are caught with evidence rather than eyeballing. The console summary
also prints the slowest classes and costliest commands at the end of each run.

## Performance Optimizations

- **One Postgres per test JVM** — `TestRuntimeLifecycle` starts a single `postgres:17` container per
  Gradle test task JVM and each Spring context gets its own logical database inside it (there is no
  cross-run container reuse; Ryuk or the launcher-session hook stops it at the end). 17 is the oldest
  supported version, so a feature only a newer server has fails the suite rather than an
  installation; `-PtestPostgresVersion=18` runs everything on 18 instead. `DataPreservationMigrationIT`
  runs the migrations on both versions regardless, starting a second server for the one the suite
  is not on
- **Not a template database** — cloning each context's database from one migrated template
  (`CREATE DATABASE … TEMPLATE`) was measured on PR #896 and dropped: the 40-plus migrations cost
  about a second per context, so the clone plus Flyway's validate pass saved 0.4 s per boot locally
  and lost 2 to 4 s per boot on CI. Context boot cost is Spring itself plus CPU contention between
  concurrent test JVMs; that is where to look
- **UNLOGGED tables** — `UnloggedTablesTestConfiguration` converts all tables to UNLOGGED after migrations, eliminating WAL writes
- **tmpfs** — PostgreSQL data directory is on tmpfs (in-memory)
- **Fake PDF generation** — `FakeDocumentGenerationExecutor` creates minimal valid PDFs instantly
- **Parallel execution** — test classes run concurrently, methods within a class run sequentially
- **Namespace isolation** — each test class uses a unique slug prefix, no cross-class interference
- **Bounded test JVMs** — a shared Gradle build service (`TestJvmSlots`, in the Kotlin convention
  plugin) caps how many Spring + Testcontainers JVMs run at once. Compilation stays fully parallel.
  Default: Gradle's worker count on CI (`CI` is set), one slot per three cores elsewhere. Override
  with `-PtestJvmSlots=N`. JUnit's class-level concurrency inside each JVM is pinned to 4 (what the
  4-core CI runner has always had; JUnit's default of one thread per core overruns the small
  per-context Hikari pools and surfaces as `Could not open JDBC Connection`); `-PtestParallelism=N`
  overrides it
- **JVM flags per task** — `unitTest` runs C1-only (`-XX:TieredStopAtLevel=1`, 512m); `test` and
  `integrationTest` also run C1-only with 1g: on the 4-core CI runner, full tiered compilation
  doubled every context boot because the C2 compiler threads of four concurrent JVMs compete with
  the boots for the same cores (measured on PR #896). `uiTest` and `perfTest` run full tiered
  compilation with 2g, as their own hardening intended
- **`stress` tag** — `@Tag("stress")` tests (e.g. `StressIT`) run in `test` (so CI covers them) but
  not in the local `integrationTest` loop
- **`model` tag** — `@Tag("model")` tests (`CatalogModelTest`) run only in `modelTest`: they take
  minutes, too long for every build

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

### A test behaves differently from production around a tenant's catalogs

Test tenants get the system catalog as a copy of a template tenant's, not installed. Create the
test's tenant with `SystemCatalogSetup.INSTALL`; if the difference goes away, see
[the template's known limits](#known-limits-and-what-to-suspect-when-a-test-misbehaves).

### Two @SpringBootApplication Conflict

If you see `Found multiple @SpringBootConfiguration` errors, ensure your test specifies `classes = [...]` in its `@SpringBootTest` annotation. The testing module's `TestApplication` and the app's `EpistolaSuiteApplication` both exist on the test classpath.
