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
import app.epistola.suite.generation.collect.queries.FetchResultsPage
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.node.JsonNodeFactory
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
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
        val page = fetch(tenant, consumer)
        assertThat(page.rows.map { it.sequence }).doesNotContain(second.sequence)
        assertThat(page.hasMore).isTrue

        first.commit()
        assertThat(collectAll(tenant, consumer)).contains(first.row.sequence, second.sequence)
    }

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

    private fun fetch(tenant: TenantKey, consumer: String): FetchResultsPage = withMediator {
        FetchGenerationResults(tenant, consumer, ALL_PARTITIONS, limit = 100).query()
    }

    private fun emit(tenant: TenantKey): GenerationResultRow = withMediator { emitCommand(tenant).execute() }

    /** An emit whose transaction stays open until [commit], on its own thread and connection. */
    private inner class EmitHeldOpen(private val tenant: TenantKey) {
        private val inserted = CompletableFuture<GenerationResultRow>()
        private val proceed = CountDownLatch(1)
        private lateinit var done: CompletableFuture<Void>

        lateinit var row: GenerationResultRow

        fun start(): EmitHeldOpen {
            done = CompletableFuture.runAsync {
                withMediator {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        inserted.complete(emitCommand(tenant).execute())
                        check(proceed.await(20, TimeUnit.SECONDS)) { "the test never released the emit" }
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
        const val MAX_POLLS = 10
    }
}
