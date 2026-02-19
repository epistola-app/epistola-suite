package app.epistola.suite

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.ScenarioFactory
import app.epistola.suite.testing.TestFixture
import app.epistola.suite.testing.TestFixtureFactory
import app.epistola.suite.testing.UnloggedTablesTestConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

@Import(TestcontainersConfiguration::class, UnloggedTablesTestConfiguration::class)
@SpringBootTest(
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
abstract class BaseIntegrationTest {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    @Autowired
    protected lateinit var scenarioFactory: ScenarioFactory

    @Autowired
    private lateinit var dataSource: DataSource

    private val classNamespace = this::class.simpleName!!.lowercase().take(20)
    private var tenantCounter = 0

    private fun nextTenantSlug(): String = "$classNamespace-${++tenantCounter}"

    protected fun <T> fixture(block: TestFixture.() -> T): T = testFixtureFactory.fixture(classNamespace, block)

    /**
     * Runs the given block with the mediator bound to the current scope.
     * This enables use of Command.execute() and Query.query() extension functions.
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
    protected fun <T> scenario(block: ScenarioBuilder.() -> T): T = scenarioFactory.scenario(classNamespace, block)

    protected fun createTenant(name: String): Tenant = withMediator {
        val tenant = CreateTenant(id = TenantId.of(nextTenantSlug()), name = name).execute()
        tenant
    }

    /**
     * Reset test data before each test.
     *
     * Uses per-class namespaced DELETE to only clean up data owned by this test class,
     * enabling safe parallel execution across test classes.
     */
    @BeforeEach
    fun resetDatabaseState() {
        tenantCounter = 0
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM tenants WHERE id LIKE ?").use { stmt ->
                stmt.setString(1, "$classNamespace-%")
                stmt.executeUpdate()
            }
        }
    }
}
