# Testing Guide

This document describes the testing infrastructure, patterns, and best practices for Epistola Suite.

## Prerequisites

- **Docker** - Required for running integration tests (Testcontainers)
- **JDK 25** - Required for running tests

## Running Tests

```bash
# Run all tests
gradle test

# Run tests with coverage report
gradle koverHtmlReport

# Run a specific test class
gradle test --tests "app.epistola.suite.tenants.TenantCommandsTest"

# Run tests in a specific package
gradle test --tests "app.epistola.suite.tenants.*"
```

## Test Frameworks

| Framework | Purpose |
|-----------|---------|
| JUnit 5 | Core testing framework |
| Testcontainers | Docker containers for integration tests |
| Spring Boot Test | Spring context and web testing |
| AssertJ | Fluent assertion library |
| Kover | Code coverage for Kotlin |

## Test Directory Structure

```
apps/epistola/src/test/kotlin/app/epistola/suite/
├── BaseIntegrationTest.kt           # Base class for integration tests
├── TestcontainersConfiguration.kt   # Testcontainers setup
├── TestEpistolaSuiteApplication.kt  # Test app entry point
├── testing/
│   └── TestFixture.kt               # Test fixture DSL
├── htmx/                            # Unit tests for HTMX utilities
├── tenants/                         # Tenant feature tests
└── templates/                       # Template feature tests
```

## Test Types

### Unit Tests

Unit tests run without Spring context and test isolated components:

```kotlin
class HtmxRequestTest {
    @Test
    fun `should detect HTMX request`() {
        val request = MockHttpServletRequest()
        request.addHeader("HX-Request", "true")

        assertThat(request.isHtmx()).isTrue()
    }
}
```

**Characteristics:**
- No `@SpringBootTest` annotation
- No database or Docker required
- Fast execution
- Test utilities, helpers, and pure functions

### Integration Tests

Integration tests use the full Spring context with a real PostgreSQL database:

```kotlin
class TenantCommandsTest : BaseIntegrationTest() {
    @Test
    fun `should create tenant`() {
        fixture {
            whenever {
                createTenant("Acme Corp")
            }
            then {
                val tenant = result<Tenant>()
                assertThat(tenant.name).isEqualTo("Acme Corp")
            }
        }
    }
}
```

**Characteristics:**
- Extend `BaseIntegrationTest`
- Full Spring context loaded
- PostgreSQL container started automatically
- Automatic cleanup after each test

## BaseIntegrationTest

All integration tests should extend `BaseIntegrationTest`:

```kotlin
@Import(TestcontainersConfiguration::class)
@SpringBootTest
abstract class BaseIntegrationTest {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    protected fun <T> fixture(block: TestFixture.() -> T): T
    protected fun createTenant(name: String): Tenant
    protected fun deleteAllTenants()
}
```

**Provides:**
- Testcontainers configuration (PostgreSQL)
- `Mediator` for executing commands and queries
- `TestFixtureFactory` for building test scenarios
- Helper methods for common operations
- Automatic cleanup after each test

## Test Fixture DSL

The `TestFixture` DSL provides a fluent, readable way to write tests using the Given-When-Then pattern:

```kotlin
fixture {
    given {
        // Setup: create prerequisites
        tenant("Existing Corp")
    }
    whenever {
        // Action: execute the operation under test
        createTenant("New Corp")
    }
    then {
        // Assert: verify the results
        val tenant = result<Tenant>()
        assertThat(tenant.name).isEqualTo("New Corp")
    }
}
```

### Available Operations

#### Given Context (Setup)

```kotlin
given {
    tenant("Acme Corp")                      // Create a tenant
    template(tenant, "Invoice Template")      // Create a document template
    noTenants()                              // Ensure no tenants exist
}
```

#### When Context (Action)

```kotlin
whenever {
    createTenant("New Corp")                 // Create a tenant
    deleteTenant(id)                         // Delete a tenant
    listTenants(searchTerm)                  // List tenants
    listTemplates(tenant)                    // List templates for a tenant
}
```

#### Then Context (Assert)

```kotlin
then {
    val result = result<Tenant>()            // Get result from when block
    assertThat(result.name).isEqualTo("Expected")
}
```

### Automatic Cleanup

The fixture automatically cleans up all created resources after the test:

```kotlin
fixture {
    given {
        tenant("Test Corp")  // Will be deleted after test
    }
    // ...
}
// Cleanup happens automatically, even if test fails
```

## HTTP/Route Testing

For testing HTTP endpoints, add the web environment configuration:

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TenantRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `should return tenant list`() {
        fixture {
            given { tenant("Acme Corp") }
            then {
                val response = restTemplate.getForEntity("/tenants", String::class.java)
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("Acme Corp")
            }
        }
    }
}
```

### Testing HTMX Endpoints

When testing HTMX-specific behavior, set the appropriate headers:

```kotlin
@Test
fun `should return fragment for HTMX request`() {
    val headers = HttpHeaders()
    headers.set("HX-Request", "true")
    headers.set("HX-Target", "tenant-list")

    val request = HttpEntity<Void>(headers)
    val response = restTemplate.exchange(
        "/tenants",
        HttpMethod.GET,
        request,
        String::class.java
    )

    // Verify HTMX response headers
    assertThat(response.headers["HX-Push-Url"]).isNotNull()
}
```

### Testing Form Submissions

```kotlin
@Test
fun `should create tenant via form`() {
    val headers = HttpHeaders()
    headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
    headers.set("HX-Request", "true")

    val body = LinkedMultiValueMap<String, String>()
    body.add("name", "New Corp")

    val request = HttpEntity(body, headers)
    val response = restTemplate.postForEntity("/tenants", request, String::class.java)

    assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
}
```

## Testcontainers Configuration

Testcontainers is configured in `TestcontainersConfiguration.kt`:

```kotlin
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
}
```

**Features:**
- Uses `@ServiceConnection` for automatic connection property configuration
- PostgreSQL container starts automatically when tests run
- Container is shared across all tests in the same test run
- No manual database cleanup required between tests (use fixtures instead)

## Running Application with Testcontainers

For local development, you can run the application with Testcontainers:

```bash
gradle :apps:epistola:bootTestRun
```

This uses `TestEpistolaSuiteApplication.kt` which starts the app with a containerized database.

## Code Coverage

Coverage is tracked using Kover (Kotlin coverage tool):

```bash
# Generate HTML coverage report
gradle koverHtmlReport

# Report location: build/reports/kover/html/index.html
```

**Excluded from coverage:**
- Spring framework code
- AOT-generated classes
- Test configuration classes

## Best Practices

### 1. Use the Fixture DSL

Always use the fixture DSL for integration tests - it ensures proper cleanup:

```kotlin
// Good
fixture {
    given { tenant("Test") }
    whenever { /* ... */ }
    then { /* ... */ }
}

// Avoid - manual setup without cleanup
val tenant = mediator.execute(CreateTenantCommand("Test"))
// May leave data in database
```

### 2. Test at the Right Level

- **Unit tests**: Utilities, helpers, pure functions
- **Integration tests**: Business logic, database operations
- **Route tests**: HTTP endpoints, form handling, HTMX behavior

### 3. Use Meaningful Test Names

```kotlin
// Good
@Test
fun `should reject duplicate tenant names`() { }

// Avoid
@Test
fun testTenant() { }
```

### 4. One Assertion Focus Per Test

```kotlin
// Good - focused test
@Test
fun `should set tenant name correctly`() {
    fixture {
        whenever { createTenant("Acme") }
        then {
            assertThat(result<Tenant>().name).isEqualTo("Acme")
        }
    }
}

// Separate test for another aspect
@Test
fun `should generate unique tenant id`() {
    fixture {
        whenever { createTenant("Acme") }
        then {
            assertThat(result<Tenant>().id).isNotNull()
        }
    }
}
```

### 5. Test Multi-Tenancy Isolation

When working with multi-tenant features, verify data isolation:

```kotlin
@Test
fun `should not access other tenant data`() {
    fixture {
        given {
            val tenant1 = tenant("Corp A")
            val tenant2 = tenant("Corp B")
            template(tenant1, "Private Template")
        }
        whenever {
            // Try to list templates for tenant2
            listTemplates(tenant2)
        }
        then {
            // Should not see tenant1's template
            assertThat(result<List<DocumentTemplate>>()).isEmpty()
        }
    }
}
```

## Troubleshooting

### Docker Not Running

```
Could not find a valid Docker environment
```

**Solution:** Start Docker Desktop or Docker daemon.

### Port Already in Use

```
Port 5432 is already in use
```

**Solution:** Testcontainers uses random ports. Check if another PostgreSQL is running and stop it, or let Testcontainers manage ports automatically.

### Tests Hanging

If tests hang, check:
1. Docker has enough resources (memory/CPU)
2. No zombie containers: `docker ps -a`
3. Clean up: `docker system prune`

### Flaky Tests

If tests pass locally but fail in CI:
1. Ensure tests don't depend on execution order
2. Use `@Order` annotation if order matters
3. Check for race conditions in async code
4. Verify fixtures clean up properly
