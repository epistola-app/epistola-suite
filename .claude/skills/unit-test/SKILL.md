---
name: unit-test
description: Create unit tests or integration tests. Use when writing tests for business logic, commands, queries, handlers, or pure functions.
---

Create tests for Epistola Suite code.

**Input**: What code or behavior to test, and whether it needs a unit test or integration test.

## Decision Points

| What you're testing | Test type | Base class | Run with |
|---------------------|-----------|------------|----------|
| Pure logic, algorithms, utilities | Unit test | None | `./gradlew unitTest` |
| Commands, queries, DB operations | Integration test | `CoreIntegrationTestBase` | `./gradlew integrationTest` |
| UI handlers, routes, HTTP | Integration test | `BaseIntegrationTest` | `./gradlew integrationTest` |
| Browser interactions, HTMX | UI test | `BasePlaywrightTest` | `./gradlew uiTest` |
| TypeScript engine logic | Vitest | N/A | `pnpm --filter @epistola/editor test` |

Tags (`@Tag("unit")`, `@Tag("integration")`, `@Tag("ui")`) are **inherited from the base classes** — do NOT add them manually.

## Kotlin Unit Tests (no Spring, no Docker)

Create co-located with the code being tested.

```kotlin
class SomeClassTest {
    @Test
    fun `describes behavior in backtick syntax`() {
        val result = pureFunction(input)
        assertThat(result).isEqualTo(expected)
    }
}
```

**Conventions**: No Spring context, no Testcontainers. Instantiate directly. Test input/output. Avoid mocking. Use AssertJ or `kotlin.test`.

## Integration Tests (commands, queries, DB)

Create in `modules/epistola-core/src/test/kotlin/app/epistola/suite/<domain>/`.

### `fixture { }` DSL

```kotlin
class FeatureTest : CoreIntegrationTestBase() {
    @Test
    fun `CreateEntity creates entity with valid data`() = fixture {
        whenever {
            CreateEntity(id = ..., tenantId = tenantId, name = "Test").execute()
        }
        then {
            val entity = result<Entity>()
            assertThat(entity.name).isEqualTo("Test")
        }
    }

    @Test
    fun `ListEntities filters by search term`() = fixture {
        given {
            CreateEntity(..., name = "Alpha").execute()
            CreateEntity(..., name = "Beta").execute()
        }
        whenever {
            ListEntities(tenantId = tenantId, searchTerm = "Alpha").query()
        }
        then {
            val results = result<List<Entity>>()
            assertThat(results).hasSize(1)
        }
    }
}
```

- `given { }` — preconditions (create entities)
- `whenever { }` — action under test
- `then { }` — assertions via `result<T>()` to get the whenever return value
- Each test gets a fresh tenant and clean state via `@BeforeEach`

### `scenario { }` DSL (type-safe)

**Reference**: Check existing tests for `scenario { }` usage in `modules/epistola-core/src/test/`

The `scenario` DSL provides type-safe Given-When-Then with automatic cleanup:

```kotlin
@Test
fun `scenario example`() = scenario {
    given("a template exists") {
        CreateDocumentTemplate(...).execute()
    }
    whenever("we create a variant") {
        CreateVariant(...).execute()
    }
    then("variant is returned") { result ->
        assertThat(result.id).isNotNull()
    }
}
```

### `withMediator { }` for simple tests

```kotlin
@Test
fun `simple test`() = withMediator {
    val entity = CreateEntity(...).execute()
    assertThat(entity.name).isEqualTo("Expected")
}
```

### `withAuthentication { }` for security context

**Only available in `CoreIntegrationTestBase`**. Use when the code under test checks the authenticated user:

```kotlin
@Test
fun `authenticated test`() = withAuthentication {
    val entity = CreateEntity(...).execute()
    // security context is bound
}
```

### TestIdHelpers

**Reference**: `modules/epistola-core/src/test/kotlin/app/epistola/suite/common/TestIdHelpers.kt`

Generates sequential unique IDs for tests:
- `TestIdHelpers.nextTemplateId()` → `TemplateId("test-template-1")`
- `TestIdHelpers.nextVariantId()` → `VariantId("test-variant-1")`
- `TestIdHelpers.nextEnvironmentId()` → `EnvironmentId("test-env-1")`
- `TestIdHelpers.nextAttributeId()` → `AttributeId("test-attr-1")`
- `TestIdHelpers.reset()` — resets all counters (called automatically in `@BeforeEach`)

### `@Nested` and `@ParameterizedTest`

Use `@Nested` inner classes to group related tests:

```kotlin
class ThemeCommandsTest : CoreIntegrationTestBase() {
    @Nested
    inner class CreateThemeTests {
        @Test
        fun `creates theme with valid data`() = fixture { ... }

        @Test
        fun `rejects duplicate id`() = fixture { ... }
    }

    @Nested
    inner class DeleteThemeTests {
        @Test
        fun `deletes existing theme`() = fixture { ... }
    }
}
```

Use `@ParameterizedTest` for testing multiple inputs:
```kotlin
@ParameterizedTest
@ValueSource(strings = ["", " ", "   "])
fun `rejects blank name`(name: String) = withMediator {
    assertThatThrownBy { CreateEntity(..., name = name).execute() }
        .isInstanceOf(ValidationException::class.java)
}
```

## Route/Handler Integration Tests (HTTP level)

Create in `apps/epistola/src/test/kotlin/app/epistola/suite/<domain>/`.

**Reference**: Existing `*RoutesTest.kt` files in `apps/epistola/src/test/`

```kotlin
class EntityRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET entity list returns page`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = createTenant("Test Tenant")
        }
        whenever {
            restTemplate.getForEntity("/tenants/${tenant.id}/entities", String::class.java)
        }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Expected Content")
        }
    }
}
```

`BaseIntegrationTest` already has `@SpringBootTest(webEnvironment = RANDOM_PORT)` — do NOT re-specify it on the test class.

## TypeScript Unit Tests (Vitest)

Create co-located as `<name>.test.ts`:

```typescript
import { describe, it, expect, vi } from 'vitest'
import { SomeClass } from './<source>.js'

describe('SomeClass', () => {
    it('does something', () => {
        const result = someFunction(input)
        expect(result).toBe(expected)
    })
})
```

Import with `.js` extension. Use `vi.fn()` for callbacks/spies. Use `test-helpers.ts` for engine fixtures.

## Run Commands

```bash
./gradlew unitTest                          # Fast, no Docker
./gradlew integrationTest                   # Spring + DB (Docker required)
./gradlew uiTest                            # Playwright browser tests
./gradlew test                              # All Kotlin tests
./gradlew integrationTest --tests 'ClassName'  # Specific test class
pnpm --filter @epistola/editor test         # TypeScript tests
```

## Gotchas

- Each test gets clean-slate DB via `@BeforeEach` — no need to manually clean up
- `fixture { }`, `withMediator { }`, and `scenario { }` all bind the `MediatorContext` — use one, not both
- Helper methods defined in your test class need to be called **inside** `withMediator { }` at the call site — the mediator context is thread-local
- `withAuthentication { }` is only on `CoreIntegrationTestBase`, not `BaseIntegrationTest`
- Avoid mocking — test input/output with real DB. Only mock external services if absolutely necessary
- Tests should execute fast — target full suite within 1-2 minutes
