// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.fonts.FontByteCache
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.ScenarioBuilder
import app.epistola.suite.testing.TestTemplateBuilder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How [JobPoller] treats in-flight renders when its instance stops (#1065).
 *
 * Each test builds its own [JobPoller] from the context's beans, with a [GatedExecutor] that holds
 * a render until the test releases it, and drives the shutdown hooks on that instance directly —
 * so the context's shared poller is never shut down.
 */
@Isolated(
    "Drives a JobPoller's global (non-tenant-scoped) drain() loop; must not race a concurrently " +
        "running test's poller for globally-pending rows",
)
@Timeout(60)
class JobPollerShutdownIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var jdbi: Jdbi

    @Autowired private lateinit var properties: JobPollingProperties

    /** The context's (fake) executor; [GatedExecutor] delegates to it once released. */
    @Autowired private lateinit var executor: DocumentGenerationExecutor

    @Autowired private lateinit var generationService: GenerationService

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var schemaValidator: JsonSchemaValidator

    @Autowired private lateinit var contentStore: DocumentContentStore

    @Autowired private lateinit var meterRegistry: MeterRegistry

    @Autowired private lateinit var releaseRenderSource: ReleaseRenderSource

    @Autowired private lateinit var draftRenderer: app.epistola.suite.generation.DraftDocumentRenderer

    @Autowired private lateinit var localeResolver: TenantLocaleResolver

    @Test
    fun `a closing context stops the poller claiming new requests`() = scenario {
        given {
            pendingRequest("not-claimed.pdf")
        }.whenever { (tenantKey, requestId) ->
            val gate = GatedExecutor.Gate(interruptible = true).also { it.release() }
            val poller = poller(gate, drainTimeout = Duration.ofSeconds(1))
            try {
                poller.onContextClosed()
                // awaitDrain runs a pass directly, bypassing requestDrain's own shutdown check,
                // so this proves the drain loop itself refuses to claim.
                poller.awaitDrain()
                statusOf(requestId)
            } finally {
                poller.shutdown()
                drainGenerationJobs(tenantKey)
            }
        }.then { _, status ->
            assertThat(status).isEqualTo("PENDING")
        }
    }

    @Test
    fun `a render still running when shutdown starts completes on the stopping instance`() = scenario {
        given {
            pendingRequest("drained.pdf")
        }.whenever { (_, requestId) ->
            // CPU-bound like a real render: ignores interrupts, so only the drain lets it finish.
            val gate = GatedExecutor.Gate(interruptible = false)
            val poller = poller(gate, drainTimeout = Duration.ofSeconds(20))
            poller.awaitDrain()
            assertThat(gate.started.await(5, TimeUnit.SECONDS)).isTrue()

            poller.onContextClosed()
            val shutdown = CompletableFuture.runAsync(poller::shutdown)
            gate.release()
            shutdown.get(10, TimeUnit.SECONDS)
            statusOf(requestId)
        }.then { _, status ->
            assertThat(status).isEqualTo("COMPLETED")
        }
    }

    @Test
    fun `a render outlasting the drain timeout is released to PENDING, not marked FAILED`() = scenario {
        given {
            pendingRequest("released.pdf")
        }.whenever { (tenantKey, requestId) ->
            val gate = GatedExecutor.Gate(interruptible = true)
            val poller = poller(gate, drainTimeout = Duration.ofMillis(500))
            poller.awaitDrain()
            assertThat(gate.started.await(5, TimeUnit.SECONDS)).isTrue()

            poller.onContextClosed()
            val startedAt = System.nanoTime()
            poller.shutdown()
            val shutdownTook = Duration.ofNanos(System.nanoTime() - startedAt)
            // The interrupted render thread finishes on its own schedule. awaitIdle returns only
            // once its failure handling has run too, so a late FAILED write cannot slip in after
            // the assertion.
            assertThat(gate.finished.await(5, TimeUnit.SECONDS)).isTrue()
            poller.awaitIdle(Duration.ofSeconds(5))
            Released(statusOf(requestId), shutdownTook).also { drainGenerationJobs(tenantKey) }
        }.then { _, released ->
            assertThat(released.status).isEqualTo("PENDING")
            // Honours the configured 500 ms rather than a fixed wait (it used to be 5 s).
            assertThat(released.shutdownTook).isLessThan(Duration.ofSeconds(3))
        }
    }

    private data class Released(val status: String, val shutdownTook: Duration)

    private fun ScenarioBuilder.GivenScope.pendingRequest(filename: String): Pair<TenantKey, GenerationRequestKey> {
        drainOtherPendingRequests()
        val tenant = tenant("Shutdown Drain")
        val tenantId = TenantId(tenant.id)
        val template = template(tenant.id, "Shutdown Drain Template")
        val compositeTemplateId = TemplateId(template.id, CatalogId.default(tenantId))
        val variant = variant(compositeTemplateId, "Default")
        val compositeVariantId = VariantId(variant.id, compositeTemplateId)
        released(compositeVariantId, TestTemplateBuilder.buildMinimal(name = "Shutdown Drain Template"))
        val request = execute(
            GenerateDocument(
                tenantId = tenant.id,
                templateId = template.id,
                variantId = variant.id,
                environmentId = null,
                data = ObjectMapper().createObjectNode(),
                filename = filename,
                catalogKey = CatalogKey.DEFAULT,
            ),
        )
        return tenant.id to request.id
    }

    /**
     * The test poller drains globally and claims the oldest PENDING rows first, so a request
     * another test class left behind would be claimed instead of this test's own. Drain those
     * through the shared poller first; @Isolated keeps new ones from appearing meanwhile.
     */
    private fun drainOtherPendingRequests() {
        val tenants = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT DISTINCT tenant_key FROM document_generation_requests WHERE status = 'PENDING'")
                .mapTo<String>()
                .list()
        }
        tenants.forEach { drainGenerationJobs(TenantKey(it)) }
    }

    /**
     * A poller of its own: own properties, batch sizer, admission controller and registry. The
     * shared admission controller can be left PAUSED by an earlier test's database-pressure
     * signal (and never recovers under the frozen test clock), so pressure gating is off here.
     */
    private fun poller(gate: GatedExecutor.Gate, drainTimeout: Duration): JobPoller {
        val pollerProperties = properties.copy(
            maxConcurrentJobs = 2,
            shutdownDrainTimeout = drainTimeout,
            databasePressure = properties.databasePressure.copy(enabled = false),
        )
        val registry = SimpleMeterRegistry()
        return JobPoller(
            jdbi = jdbi,
            jobExecutor = GatedExecutor(
                gate, executor, jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore,
                meterRegistry, localeResolver, releaseRenderSource, draftRenderer,
            ),
            properties = pollerProperties,
            batchSizer = AdaptiveBatchSizer(pollerProperties, registry),
            admissionController = DatabasePressureAdmissionController(pollerProperties, { error("pressure gating is off") }, registry),
            meterRegistry = registry,
            mediator = mediator,
        )
    }

    private fun statusOf(requestId: GenerationRequestKey): String = jdbi.withHandle<String, Exception> { handle ->
        handle.createQuery("SELECT status FROM document_generation_requests WHERE id = :id")
            .bind("id", requestId)
            .mapTo<String>()
            .one()
    }

    /** Holds each render at [Gate] until released, then delegates to the context's executor. */
    class GatedExecutor(
        private val gate: Gate,
        private val delegate: DocumentGenerationExecutor,
        jdbi: Jdbi,
        generationService: GenerationService,
        mediator: Mediator,
        objectMapper: ObjectMapper,
        schemaValidator: JsonSchemaValidator,
        contentStore: DocumentContentStore,
        meterRegistry: MeterRegistry,
        localeResolver: TenantLocaleResolver,
        releaseRenderSource: ReleaseRenderSource,
        draftRenderer: app.epistola.suite.generation.DraftDocumentRenderer,
    ) : DocumentGenerationExecutor(
        jdbi, generationService, mediator, objectMapper, schemaValidator, contentStore, meterRegistry,
        localeResolver, releaseRenderSource, draftRenderer, retentionDays = 7, maxDocumentSizeMb = 50,
    ) {
        /** [interruptible] false models a CPU-bound render that does not stop on interrupt. */
        class Gate(val interruptible: Boolean) {
            private val open = CountDownLatch(1)
            val started = CountDownLatch(1)
            val finished = CountDownLatch(1)

            fun release() = open.countDown()

            fun await() {
                if (interruptible) {
                    open.await()
                    return
                }
                while (true) {
                    try {
                        open.await()
                        return
                    } catch (_: InterruptedException) {
                        // keep rendering
                    }
                }
            }
        }

        override fun execute(request: DocumentGenerationRequest) {
            gate.started.countDown()
            try {
                gate.await()
                delegate.execute(request)
            } finally {
                gate.finished.countDown()
            }
        }
    }
}
