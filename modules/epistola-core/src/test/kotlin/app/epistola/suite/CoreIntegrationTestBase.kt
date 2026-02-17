package app.epistola.suite

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.FakeExecutorTestConfiguration
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.ScenarioFactory
import app.epistola.suite.testing.TestFixture
import app.epistola.suite.testing.TestFixtureFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(CoreTestcontainersConfiguration::class, FakeExecutorTestConfiguration::class)
@SpringBootTest(
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
abstract class CoreIntegrationTestBase {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    @Autowired
    protected lateinit var scenarioFactory: ScenarioFactory

    private val createdTenants = mutableListOf<TenantId>()
    private var tenantCounter = 0

    private fun nextTenantSlug(): String = "test-tenant-${++tenantCounter}"

    /**
     * Test user for authenticated operations.
     * Has access to all test tenants created during tests.
     */
    protected val testUser = EpistolaPrincipal(
        userId = UserId.of("00000000-0000-0000-0000-000000000099"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = emptySet(), // Will be updated as tenants are created
        currentTenantId = null,
    )

    protected fun <T> fixture(block: TestFixture.() -> T): T = testFixtureFactory.fixture(block)

    /**
     * Runs the given block with both mediator and authenticated user context.
     *
     * This is the primary method for tests that need to perform authenticated operations.
     *
     * Usage:
     * ```kotlin
     * withAuthentication {
     *     val tenant = CreateTenant("name").execute()
     * }
     * ```
     */
    protected fun <T> withAuthentication(block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(testUser) {
            block()
        }
    }

    /**
     * Runs the given block with the mediator bound to the current scope.
     * This enables use of Command.execute() and Query.query() extension functions.
     *
     * Note: For most tests, use `withAuthentication` instead to also bind the security context.
     *
     * Usage:
     * ```kotlin
     * withMediator {
     *     val tenant = CreateTenant("name").execute()
     *     val tenants = ListTenants().query()
     * }
     * ```
     */
    protected fun <T> withMediator(block: () -> T): T = MediatorContext.runWithMediator(mediator, block)

    /**
     * Creates a test scenario with type-safe Given-When-Then DSL and automatic cleanup.
     *
     * Example:
     * ```kotlin
     * @Test
     * fun `generate document successfully`() = scenario {
     *     given {
     *         val tenant = tenant("Test Tenant")
     *         val template = template(tenant.id, "Invoice")
     *         val variant = variant(tenant.id, template.id)
     *         val version = version(tenant.id, template.id, variant.id, templateModel)
     *         DocumentSetup(tenant, template, variant, version)
     *     }.whenever { setup ->
     *         execute(GenerateDocument(setup.tenant.id, ...))
     *     }.then { setup, result ->
     *         assertThat(result.id).isNotNull()
     *     }
     * }
     * ```
     */
    protected fun <T> scenario(block: ScenarioBuilder.() -> T): T = scenarioFactory.scenario(block)

    protected fun createTenant(name: String): Tenant = withMediator {
        val tenant = CreateTenant(id = TenantId.of(nextTenantSlug()), name = name).execute()
        createdTenants.add(tenant.id)
        tenant
    }

    protected fun deleteAllTenants(): Unit = withMediator {
        ListTenants().query().forEach { tenant ->
            DeleteTenant(tenant.id).execute()
        }
        createdTenants.clear()
    }

    /**
     * Reset the database state before each test.
     *
     * With synchronous job execution enabled in tests, jobs complete immediately
     * so there's no need to wait for pending/in-progress jobs.
     */
    @BeforeEach
    fun resetDatabaseState(): Unit = withMediator {
        val tenants = ListTenants().query()
        if (tenants.isEmpty()) return@withMediator

        // Delete all tenants to ensure a clean slate
        // With synchronous execution, jobs are already complete
        deleteAllTenants()
    }

    @AfterEach
    fun cleanUpCreatedTenants(): Unit = withMediator {
        createdTenants.forEach { tenantId ->
            DeleteTenant(tenantId).execute()
        }
        createdTenants.clear()
    }
}
