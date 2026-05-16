package app.epistola.suite.testing

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.batch.JobPoller
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * Base class for integration tests across all modules.
 *
 * Provides:
 * - Spring Boot test context with [TestApplication] (modules can override with their own app class)
 * - Testcontainers PostgreSQL, unlogged tables, fake document generation
 * - Mediator + security context binding via [withMediator]
 * - Test fixture and scenario DSLs
 * - Tenant creation helpers
 *
 * Module tests extend this directly. App tests extend [this class] with their own
 * `@SpringBootTest(classes = [EpistolaSuiteApplication::class])` which overrides the default.
 */
@SpringBootTest(
    classes = [TestApplication::class],
    properties = ["epistola.demo.enabled=false"],
)
@Import(
    TestcontainersConfiguration::class,
    FakeExecutorTestConfiguration::class,
    UnloggedTablesTestConfiguration::class,
)
@ActiveProfiles("test")
@Tag("integration")
abstract class IntegrationTestBase {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired(required = false)
    private var jobPoller: JobPoller? = null

    @Autowired
    private lateinit var integrationTestJdbi: Jdbi

    @BeforeEach
    fun awaitIdleJobPoller() {
        jobPoller?.awaitIdle()
    }

    /**
     * Idempotently materialise a `users` row for [principal] so it satisfies the
     * audit foreign keys (`created_by` / `updated_by`). Tests that authenticate
     * as their own ad-hoc principals call this (or use [runAs], which calls it).
     */
    protected fun ensureUser(principal: EpistolaPrincipal): Unit = TestPrincipalUsers.ensure(integrationTestJdbi, principal)

    @Autowired
    protected lateinit var testFixtureFactory: TestFixtureFactory

    @Autowired
    protected lateinit var scenarioFactory: ScenarioFactory

    private val classNamespace = this::class.simpleName!!.lowercase().take(20)

    private fun nextTenantSlug(): String = "$classNamespace-${TestTenantCounter.next(classNamespace)}"

    protected val testUser = EpistolaPrincipal(
        userId = TestPrincipalUser.ID,
        externalId = TestPrincipalUser.EXTERNAL_ID,
        email = TestPrincipalUser.EMAIL,
        displayName = TestPrincipalUser.DISPLAY_NAME,
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        currentTenantId = null,
    )

    protected fun <T> fixture(block: TestFixture.() -> T): T = testFixtureFactory.fixture(classNamespace, block)

    protected fun <T> withAuthentication(block: () -> T): T = withMediator(block)

    protected fun <T> withMediator(block: () -> T): T = runAs(testUser, block)

    /**
     * Run [block] under [principal] with the mediator bound. The principal's
     * `users` row is ensured first so audit foreign keys are always satisfiable
     * — use this instead of `SecurityContext.runWithPrincipal` directly in tests.
     */
    protected fun <T> runAs(principal: EpistolaPrincipal, block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        TestPrincipalUsers.runWithPrincipal(integrationTestJdbi, principal, block)
    }

    protected fun <T> scenario(block: ScenarioBuilder.() -> T): T = scenarioFactory.scenario(classNamespace, block)

    protected fun createTenant(name: String): Tenant = withMediator {
        CreateTenant(id = TenantKey.of(nextTenantSlug()), name = name).execute()
    }
}
