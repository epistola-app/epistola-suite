// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.commands.AcknowledgeGenerationResults
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class FetchGenerationResultsHandlerIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private fun tenant() = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
    private fun consumerId() = "cons-${UUID.randomUUID()}"

    /**
     * Seed a generation_results row for a specific tenant + partition. Returns
     * the auto-assigned `sequence`. Bypasses the `EmitGenerationResult` command
     * because tests want fine-grained control over partition assignment without
     * synthesizing a full DocumentGenerationRequest each time.
     */
    private fun appendRow(tenantKey: TenantKey, partition: Int): Long = jdbi.withHandle<Long, Exception> { h ->
        h.createQuery(
            """
            INSERT INTO generation_results (
                partition, request_id, tenant_key, routing_key, status, completed_at
            )
            VALUES (
                :partition, :requestId, :tenantKey, :routingKey, :status, :completedAt
            )
            RETURNING sequence
            """,
        )
            .bind("partition", partition)
            .bind("requestId", UUID.randomUUID())
            .bind("tenantKey", tenantKey)
            .bind("routingKey", "rk-${UUID.randomUUID()}")
            .bind("status", ResultStatus.COMPLETED.name)
            .bind("completedAt", OffsetDateTime.now())
            .mapTo<Long>()
            .one()
    }

    @Test
    fun `returns empty page when no results exist`() {
        val page = withMediator {
            FetchGenerationResults(tenant(), consumerId(), setOf(0, 7, 33), limit = 100).query()
        }
        assertThat(page.rows).isEmpty()
        assertThat(page.hasMore).isFalse
        assertThat(page.lastSequence).isNull()
    }

    @Test
    fun `returns empty page when partition set is empty`() {
        val page = withMediator {
            FetchGenerationResults(tenant(), consumerId(), emptySet(), limit = 100).query()
        }
        assertThat(page.rows).isEmpty()
        assertThat(page.hasMore).isFalse
    }

    @Test
    fun `returns rows from the consumer's assigned partitions only`() {
        val tenant = tenant()
        val consumer = consumerId()

        val a = appendRow(tenant, partition = 3)
        val b = appendRow(tenant, partition = 7)
        appendRow(tenant, partition = 11) // not in our assignment

        val page = withMediator {
            FetchGenerationResults(tenant, consumer, setOf(3, 7), limit = 100).query()
        }

        assertThat(page.rows.map { it.sequence }).containsExactlyInAnyOrder(a, b)
        assertThat(page.hasMore).isFalse
        assertThat(page.lastSequence).isEqualTo(maxOf(a, b))
    }

    @Test
    fun `respects per-partition cursor advanced via Acknowledge command`() {
        val tenant = tenant()
        val consumer = consumerId()
        val a = appendRow(tenant, partition = 3)
        val b = appendRow(tenant, partition = 3)
        val c = appendRow(tenant, partition = 3)

        // Ack through `a` for partition 3 — only b and c remain.
        withMediator {
            AcknowledgeGenerationResults(tenant, consumer, setOf(3), a).execute()
        }

        val page = withMediator {
            FetchGenerationResults(tenant, consumer, setOf(3), limit = 100).query()
        }

        assertThat(page.rows.map { it.sequence }).containsExactly(b, c)
        assertThat(page.lastSequence).isEqualTo(c)
    }

    @Test
    fun `hasMore is true when more rows exist than the limit allows`() {
        val tenant = tenant()
        val seqs = (1..5).map { appendRow(tenant, partition = 0) }

        val page = withMediator {
            FetchGenerationResults(tenant, consumerId(), setOf(0), limit = 3).query()
        }

        assertThat(page.rows).hasSize(3)
        assertThat(page.rows.map { it.sequence }).containsExactly(seqs[0], seqs[1], seqs[2])
        assertThat(page.hasMore).isTrue
        assertThat(page.lastSequence).isEqualTo(seqs[2])
    }

    @Test
    fun `hasMore is false when results exactly fill the page`() {
        val tenant = tenant()
        repeat(3) { appendRow(tenant, partition = 0) }

        val page = withMediator {
            FetchGenerationResults(tenant, consumerId(), setOf(0), limit = 3).query()
        }

        assertThat(page.rows).hasSize(3)
        assertThat(page.hasMore).isFalse
    }

    @Test
    fun `multi-consumer isolation - two consumers see the same rows but track cursors independently`() {
        // Different X-API-Keys polling the same tenant: both see all rows the
        // first time, and ack independently. Pinned in plan-suite.md as the
        // expected behavior for the rare multi-consumer-per-tenant case.
        val tenant = tenant()
        val ca = consumerId()
        val cb = consumerId()
        val emitted = (1..3).map { appendRow(tenant, partition = 0) }

        val pageA = withMediator { FetchGenerationResults(tenant, ca, setOf(0), 100).query() }
        val pageB = withMediator { FetchGenerationResults(tenant, cb, setOf(0), 100).query() }

        assertThat(pageA.rows.map { it.sequence }).containsExactlyElementsOf(emitted)
        assertThat(pageB.rows.map { it.sequence }).containsExactlyElementsOf(emitted)

        // ca acks all 3; cb still sees them.
        withMediator { AcknowledgeGenerationResults(tenant, ca, setOf(0), emitted.last()).execute() }
        val pageAAfter = withMediator { FetchGenerationResults(tenant, ca, setOf(0), 100).query() }
        val pageBAfter = withMediator { FetchGenerationResults(tenant, cb, setOf(0), 100).query() }
        assertThat(pageAAfter.rows).isEmpty()
        assertThat(pageBAfter.rows).hasSize(3)
    }

    @Test
    fun `cross-tenant isolation - consumer in tenant A sees no results from tenant B`() {
        val ta = tenant()
        val tb = tenant()
        appendRow(ta, partition = 0)
        appendRow(tb, partition = 0)

        val pageA = withMediator { FetchGenerationResults(ta, consumerId(), setOf(0), 100).query() }
        val pageB = withMediator { FetchGenerationResults(tb, consumerId(), setOf(0), 100).query() }

        assertThat(pageA.rows).hasSize(1)
        assertThat(pageA.rows.first().tenantKey).isEqualTo(ta)
        assertThat(pageB.rows).hasSize(1)
        assertThat(pageB.rows.first().tenantKey).isEqualTo(tb)
    }

    @Test
    fun `cursor resume after restart - Acknowledge then re-Fetch returns no rows`() {
        // Models the "consumer restarts and continues from its persistent cursor"
        // scenario. Persistent cursor is in `consumer_partition_cursors`; this test
        // confirms a fresh Fetch after Ack respects it without any in-memory state.
        val tenant = tenant()
        val consumer = consumerId()
        val emitted = (1..5).map { appendRow(tenant, partition = 0) }

        withMediator { AcknowledgeGenerationResults(tenant, consumer, setOf(0), emitted.last()).execute() }
        // Simulate a "restart" by issuing a fresh query with the same arguments.
        val page = withMediator { FetchGenerationResults(tenant, consumer, setOf(0), 100).query() }

        assertThat(page.rows).isEmpty()
        assertThat(page.lastSequence).isNull()
    }

    @Test
    fun `rejects out-of-range limit`() {
        assertThatThrownBy { FetchGenerationResults(tenant(), consumerId(), setOf(0), limit = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { FetchGenerationResults(tenant(), consumerId(), setOf(0), limit = 10001) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
