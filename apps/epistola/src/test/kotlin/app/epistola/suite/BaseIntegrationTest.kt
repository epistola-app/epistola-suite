package app.epistola.suite

import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.ScenarioFactory
import app.epistola.suite.testing.TestFixture
import app.epistola.suite.testing.TestFixtureFactory
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "epistola.demo.enabled=false",
    ],
)
abstract class BaseIntegrationTest {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    @Autowired
    protected lateinit var scenarioFactory: ScenarioFactory

    private val createdTenants = mutableListOf<Long>()

    protected fun <T> fixture(block: TestFixture.() -> T): T = testFixtureFactory.fixture(block)

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

    protected fun createTenant(name: String): Tenant {
        val tenant = mediator.send(CreateTenant(name))
        createdTenants.add(tenant.id)
        return tenant
    }

    protected fun deleteAllTenants() {
        mediator.query(ListTenants()).forEach { tenant ->
            mediator.send(DeleteTenant(tenant.id))
        }
        createdTenants.clear()
    }

    /**
     * Reset the database state before each test.
     * This ensures complete test isolation by:
     * 1. Waiting for any in-progress jobs to complete (avoiding orphaned jobs)
     * 2. Deleting all tenants (cascades to delete all related data)
     */
    @BeforeEach
    fun resetDatabaseState() {
        val tenants = mediator.query(ListTenants())
        if (tenants.isEmpty()) return

        // First, wait for any pending/in-progress jobs to complete
        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(200, TimeUnit.MILLISECONDS)
            .until {
                tenants.all { tenant ->
                    val pendingJobs = mediator.query(
                        ListGenerationJobs(
                            tenantId = tenant.id,
                            status = RequestStatus.PENDING,
                        ),
                    )
                    val inProgressJobs = mediator.query(
                        ListGenerationJobs(
                            tenantId = tenant.id,
                            status = RequestStatus.IN_PROGRESS,
                        ),
                    )
                    pendingJobs.isEmpty() && inProgressJobs.isEmpty()
                }
            }

        // Then delete all tenants to ensure a clean slate
        deleteAllTenants()
    }

    @AfterEach
    fun cleanUpCreatedTenants() {
        createdTenants.forEach { tenantId ->
            mediator.send(DeleteTenant(tenantId))
        }
        createdTenants.clear()
    }
}
