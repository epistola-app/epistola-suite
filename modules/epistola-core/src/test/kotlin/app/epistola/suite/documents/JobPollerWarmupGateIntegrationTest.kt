package app.epistola.suite.documents

import app.epistola.suite.cluster.WallClockClusterSchedulingDriver
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.batch.JobPoller
import app.epistola.suite.documents.batch.RenderWarmupGate
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestPrincipalUsers
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

/**
 * Regression test for the render-warmup-gate drain latch (#724 follow-up).
 *
 * The [JobPoller] coalesces drain requests behind a single `drainRequested` flag: a
 * [JobPoller.requestDrain] only submits a new drain when the flag flips false→true.
 * When the render-warmup gate is closed, `drain()` defers. The bug: it deferred
 * *without releasing the flag*, so the flag stayed stuck true and every later
 * `requestDrain()` — including the scheduled poll's — no-opped forever. If the first
 * drain lost the startup race with warmup, the node never drained again: a permanent
 * `processing=0` wedge (reproduced under the multi-instance harness).
 *
 * This drives that exact sequence deterministically: defer while the gate is closed,
 * then confirm that opening the gate and requesting a drain again actually processes
 * the pending request. With the bug the final request no-ops and the row stays PENDING.
 *
 * The poll interval is set huge so the background scheduler never fires — the drain is
 * driven entirely by explicit `requestDrain()` calls, isolating the latch behaviour.
 */
@SpringBootTest(
    classes = [app.epistola.suite.testing.TestApplication::class],
    properties = [
        "epistola.demo.enabled=false",
    ],
)
@Import(TestcontainersConfiguration::class, app.epistola.suite.testing.UnloggedTablesTestConfiguration::class)
@TestPropertySource(
    properties = [
        "epistola.generation.polling.enabled=true",
        // Huge interval: the scheduled tick must not fire during the test, so every drain
        // is an explicit requestDrain() we control.
        "epistola.generation.polling.interval-ms=3600000",
        "epistola.generation.polling.max-concurrent-jobs=5",
    ],
)
@Tag("integration")
@Timeout(60)
class JobPollerWarmupGateIntegrationTest {
    @Autowired
    private lateinit var mediator: Mediator

    @Autowired
    private lateinit var jobPoller: JobPoller

    @Autowired
    private lateinit var warmupGate: RenderWarmupGate

    // Not extending IntegrationTestBase: require the production wall-clock scheduling
    // substrate so the JobPoller bean is wired exactly as in production.
    @Autowired
    private lateinit var wallClockDriver: WallClockClusterSchedulingDriver

    private val objectMapper = ObjectMapper()

    private val testUser = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000099"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        currentTenantId = null,
    )

    private fun <T> withAuthentication(block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        TestPrincipalUsers.runWithPrincipal(mediator, testUser, block)
    }

    @Test
    fun `a drain deferred by the warmup gate still processes once the gate opens`(): Unit = withAuthentication {
        // Gate closed: simulate the startup window where warmup has not finished yet.
        warmupGate.beginWarmup()

        val tenant = mediator.send(
            CreateTenant(
                id = TenantKey.of("gate-tenant-${System.currentTimeMillis()}"),
                name = "Gate Tenant",
            ),
        )
        val tenantId = TenantId(tenant.id)
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = mediator.send(CreateDocumentTemplate(id = templateId, name = "Gate Template"))
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = mediator.send(
            CreateVariant(id = variantId, title = "Default", description = null, attributes = emptyMap()),
        )!!
        val version = mediator.send(
            UpdateDraft(variantId = variantId, templateModel = TestTemplateBuilder.buildMinimal(name = "Gate Template")),
        )!!

        val request = mediator.send(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                versionId = version.id,
                environmentId = null,
                data = objectMapper.createObjectNode().put("test", "value"),
                filename = "gate-test.pdf",
            ),
        )
        assertThat(request.status).isEqualTo(RequestStatus.PENDING)

        // First drain request while the gate is closed: drain() must defer. Fire two, to
        // prove multiple deferrals don't wedge the latch either.
        jobPoller.requestDrain()
        jobPoller.requestDrain()

        // Give the deferred drain(s) time to run and return; the request must stay PENDING
        // (the gate held it back — nothing rendered).
        await()
            .during(1, TimeUnit.SECONDS)
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                SecurityContext.runWithPrincipal(testUser) {
                    val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.PENDING)
                }
            }

        // Warmup finishes: open the gate and request a drain again. With the latch bug the
        // flag is stuck true from the deferred drain, so this requestDrain() no-ops and the
        // request stays PENDING forever. With the fix it drains and completes.
        warmupGate.markReady()
        jobPoller.requestDrain()

        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(250, TimeUnit.MILLISECONDS)
            .untilAsserted {
                SecurityContext.runWithPrincipal(testUser) {
                    val job = mediator.query(GetGenerationJob(tenant.id, request.id))
                    assertThat(job!!.request.status).isEqualTo(RequestStatus.COMPLETED)
                }
            }
    }
}
