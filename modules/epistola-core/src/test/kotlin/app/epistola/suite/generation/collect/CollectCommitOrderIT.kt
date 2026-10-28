// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.generation.collect.commands.AcknowledgeGenerationResults
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.GenerationResultRow
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.generation.collect.queries.FetchGenerationResults
import app.epistola.suite.generation.collect.queries.FetchGenerationResultsHandler
import app.epistola.suite.generation.collect.queries.FetchResultsPage
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Results commit in a different order than their sequences are handed out: an emit can take
 * sequence n, a second one n + 1 and commit first. A consumer that is given n + 1 acknowledges it,
 * which moves every cursor past n — so n, committing a moment later, was never delivered. The load
 * test lost 13 of 50,000 results this way. These tests hold the first emit's transaction open on
 * purpose, so the race happens every time instead of once in a few thousand.
 */
@Timeout(30)
class CollectCommitOrderIT : IntegrationTestBase() {

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a result that commits after a later one is still delivered`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val consumer = "cons-${UUID.randomUUID()}"

        val first = EmitHeldOpen(tenant).start()
        val second = emit(tenant)
        assertThat(second.sequence).isGreaterThan(first.row.sequence)

        // The first emit commits while the consumer polls. Whichever comes first, the poll waits
        // for the first result rather than handing out the second one alone.
        val release = CompletableFuture.runAsync { first.commit() }
        val delivered = collectAll(tenant, consumer)
        release.join()

        assertThat(delivered).contains(first.row.sequence, second.sequence)
    }

    @Test
    fun `a poll that gives up waiting delivers nothing past the result still in flight`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val consumer = "cons-${UUID.randomUUID()}"

        val first = EmitHeldOpen(tenant).start()
        val second = emit(tenant)

        // Held open longer than a poll waits: the poll returns, but without the second result.
        val timedOutBefore = waits(FetchGenerationResultsHandler.OUTCOME_TIMED_OUT)
        val page = fetch(tenant, consumer, inFlightWait = Duration.ofMillis(300))
        assertThat(waits(FetchGenerationResultsHandler.OUTCOME_TIMED_OUT)).isEqualTo(timedOutBefore + 1)
        assertThat(page.rows.map { it.sequence }).doesNotContain(second.sequence)
        assertThat(page.hasMore).isTrue

        first.commit()
        assertThat(collectAll(tenant, consumer)).contains(first.row.sequence, second.sequence)
    }

    @Test
    fun `an emit is never held up by a poll waiting for another one`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val consumer = "cons-${UUID.randomUUID()}"

        val first = EmitHeldOpen(tenant).start()
        emit(tenant)
        val settledBefore = waits(FetchGenerationResultsHandler.OUTCOME_SETTLED)

        // The poll waits for the first emit, which stays open; meanwhile a third emit commits. Only
        // once the poll is seen waiting: a poll that has not started yet proves nothing.
        val pollStarted = databaseNow()
        val poll = CompletableFuture.supplyAsync({ fetch(tenant, consumer) }, Executors.newVirtualThreadPerTaskExecutor())
        awaitPollWaiting(since = pollStarted)
        val third = emit(tenant)
        // The third emit is done while the poll still waits: an emit never waits for a poll.
        assertThat(poll).isNotDone

        first.commit()
        poll.get(5, TimeUnit.SECONDS)
        assertThat(waits(FetchGenerationResultsHandler.OUTCOME_SETTLED)).isGreaterThan(settledBefore)
        assertThat(collectAll(tenant, consumer) + poll.get().rows.map { it.sequence })
            .contains(first.row.sequence, third.sequence)
    }

    @Test
    fun `an emit that rolls back leaves a gap that does not stall delivery`() {
        val tenant = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
        val consumer = "cons-${UUID.randomUUID()}"

        val rolledBack = EmitHeldOpen(tenant).start()
        val later = emit(tenant)
        rolledBack.rollBack()

        // The rolled-back sequence never appears; the later result is delivered on the first poll,
        // without waiting, since nothing is in flight any more.
        val page = fetch(tenant, consumer)
        assertThat(page.rows.map { it.sequence }).contains(later.sequence).doesNotContain(rolledBack.row.sequence)
    }

    private fun databaseNow(): OffsetDateTime = jdbi.withHandle<OffsetDateTime, Exception> {
        it.createQuery("SELECT clock_timestamp()").mapTo(OffsetDateTime::class.java).one()
    }

    /**
     * Until a poll that began after [since] sits in its wait for emits in flight: its transaction
     * has run the in-flight check (`EmitsInFlight.anyStillInFlight`), which only the wait does.
     */
    private fun awaitPollWaiting(since: OffsetDateTime) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiting = jdbi.withHandle<Boolean, Exception> {
                it.createQuery(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid()
                          AND xact_start >= :since AND query LIKE '%virtualtransaction = ANY(%'
                    )
                    """,
                ).bind("since", since).mapTo(Boolean::class.java).one()
            }
            if (waiting) return
            Thread.sleep(5)
        }
        error("the poll never started waiting for the emit in flight")
    }

    /** Polls that waited for emits in flight so far, by outcome. */
    private fun waits(outcome: String): Long = meterRegistry.find(FetchGenerationResultsHandler.WAIT_METER).tag("outcome", outcome).timer()?.count() ?: 0L

    /** Poll and acknowledge like a consumer until a poll comes back empty; returns what arrived. */
    private fun collectAll(tenant: TenantKey, consumer: String): List<Long> {
        val delivered = mutableListOf<Long>()
        repeat(MAX_POLLS) {
            val page = fetch(tenant, consumer)
            delivered += page.rows.map { it.sequence }
            val last = page.lastSequence ?: return delivered
            withMediator {
                AcknowledgeGenerationResults(tenant, consumer, ALL_PARTITIONS, acknowledgeUpTo = last).execute()
            }
        }
        return delivered
    }

    /**
     * One poll. It waits [PATIENT] by default rather than the production limit, so a slow machine
     * cannot turn a poll the test means to settle into one that gives up.
     */
    private fun fetch(tenant: TenantKey, consumer: String, inFlightWait: Duration = PATIENT): FetchResultsPage = withMediator {
        FetchGenerationResults(tenant, consumer, ALL_PARTITIONS, limit = 100, inFlightWait = inFlightWait).query()
    }

    private fun emit(tenant: TenantKey): GenerationResultRow = withMediator { emitCommand(tenant).execute() }

    /** An emit whose transaction stays open until [commit], on its own thread and connection. */
    private inner class EmitHeldOpen(private val tenant: TenantKey) {
        private val inserted = CompletableFuture<GenerationResultRow>()
        private val proceed = CountDownLatch(1)

        @Volatile private var rollBack = false
        private lateinit var done: CompletableFuture<Void>

        lateinit var row: GenerationResultRow

        fun start(): EmitHeldOpen {
            done = CompletableFuture.runAsync {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        inserted.complete(emitCommand(tenant).execute())
                        check(proceed.await(20, TimeUnit.SECONDS)) { "the test never released the emit" }
                        if (rollBack) it.setRollbackOnly()
                    }
                }
            }
            row = inserted.get(10, TimeUnit.SECONDS)
            return this
        }

        fun commit() {
            proceed.countDown()
            done.get(10, TimeUnit.SECONDS)
        }

        fun rollBack() {
            rollBack = true
            commit()
        }
    }

    private fun emitCommand(tenant: TenantKey) = EmitGenerationResult(
        request = DocumentGenerationRequest(
            id = GenerationRequestKey.of(UUID.randomUUID()),
            batchId = BatchKey.of(UUID.randomUUID()),
            tenantKey = tenant,
            catalogKey = CatalogKey.DEFAULT,
            templateKey = TemplateKey.of("invoice"),
            variantKey = VariantKey.of("default"),
            versionKey = null,
            environmentKey = EnvironmentKey.of("production"),
            releaseVersion = null,
            data = JsonNodeFactory.instance.objectNode(),
            filename = "doc.pdf",
            correlationId = null,
            routingKey = null,
            documentKey = null,
            status = RequestStatus.IN_PROGRESS,
            claimedBy = "test-instance",
            claimedAt = OffsetDateTime.now(),
            errorMessage = null,
            createdAt = OffsetDateTime.now(),
            startedAt = OffsetDateTime.now(),
            completedAt = null,
            expiresAt = null,
        ),
        status = ResultStatus.COMPLETED,
        documentId = DocumentKey.of(UUID.randomUUID()),
        sizeBytes = 1L,
        contentType = "application/pdf",
        error = null,
        completedAt = OffsetDateTime.now(),
    )

    private companion object {
        val ALL_PARTITIONS = (0 until Partition.TOTAL_PARTITIONS).toSet()

        /** Long enough that no poll in these tests gives up unless the test asks it to. */
        val PATIENT: Duration = Duration.ofSeconds(15)
        const val MAX_POLLS = 10
    }
}
