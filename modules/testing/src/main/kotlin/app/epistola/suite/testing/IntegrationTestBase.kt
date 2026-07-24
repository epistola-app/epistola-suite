// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.documents.batch.JobPoller
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

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
    PartitionBootstrapTestConfiguration::class,
    DeterministicSchedulingTestConfiguration::class,
    app.epistola.suite.testing.metrics.MediatorMetricsConfiguration::class,
)
// Inherited and merged into subclasses (unlike @SpringBootTest properties, which a
// subclass's own @SpringBootTest replaces wholesale) — selects the deterministic
// scheduling substrate for every integration test. See DeterministicClusterScheduling.
@TestPropertySource(properties = ["epistola.cluster.scheduling-substrate=test"])
@ActiveProfiles("test")
@Tag("integration")
@ExtendWith(EpistolaClockExtension::class)
abstract class IntegrationTestBase {
    @Autowired
    protected lateinit var mediator: Mediator

    @Autowired(required = false)
    private var jobPoller: JobPoller? = null

    @Autowired(required = false)
    private var deterministicScheduling: DeterministicClusterScheduling? = null

    protected val testClock: MutableClock
        get() = EpistolaClockExtension.current()

    /**
     * Deterministic driver for cluster timers and scheduled tasks. Use
     * `scheduling.advanceTimeBy(...)` to simulate time passing *and* run the
     * work that became due, or `scheduling.runDue()` to run what is already due.
     * [testClock] alone moves time without firing anything.
     */
    protected val scheduling: DeterministicClusterScheduling
        get() = deterministicScheduling
            ?: error("Deterministic scheduling is not active — epistola.cluster.scheduling-substrate is overridden away from 'test'")

    /**
     * Synchronously processes [tenantKey]'s PENDING document-generation jobs and returns
     * the count. Generation is **opt-in** in tests: nothing drains automatically (no
     * enqueue event, no autonomous poll under the test substrate), so a test only renders
     * documents when it explicitly calls this. Tests that just assert on the created
     * request (PENDING status, metadata, validation) should NOT call it. Tenant-scoped, so
     * it never touches another concurrently-running test's jobs. No-op (returns 0) when the
     * `JobPoller` bean is absent (e.g. `epistola.generation.polling.enabled=false`).
     */
    protected fun drainGenerationJobs(tenantKey: TenantKey): Int = jobPoller?.drainTenant(tenantKey) ?: 0

    @BeforeEach
    fun awaitIdleJobPoller() {
        jobPoller?.awaitIdle()
    }

    /**
     * Idempotently materialise a `users` row for [principal] (via the production
     * `EnsureUser` command) so it satisfies the audit foreign keys
     * (`created_by` / `updated_by`). Tests that authenticate as their own ad-hoc
     * principals call this (or use [runAs], which calls it).
     */
    protected fun ensureUser(principal: EpistolaPrincipal): Unit = TestPrincipalUsers.ensure(mediator, principal)

    /** Materialise a `users` row for a specific [id] (for tests that pass an explicit `createdBy`). */
    protected fun ensureUser(
        id: UserKey,
        externalId: String,
        email: String,
        displayName: String,
    ): Unit = TestPrincipalUsers.ensure(mediator, id, externalId, email, displayName)

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
        TestPrincipalUsers.runWithPrincipal(mediator, principal, block)
    }

    protected fun <T> scenario(block: ScenarioBuilder.() -> T): T = scenarioFactory.scenario(classNamespace, block)

    protected fun createTenant(name: String): Tenant = withMediator {
        CreateTenant(id = TenantKey.of(nextTenantSlug()), name = name).execute()
    }
}
