package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.generation.collect.domain.Partition
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class TouchConsumerNodeHandlerIT : IntegrationTestBase() {

    private fun tenant() = TenantKey.of("t-${UUID.randomUUID().toString().take(8)}")
    private fun consumerId() = "cons-${UUID.randomUUID()}"

    @Test
    fun `lone node is assigned every partition`() {
        val tenant = tenant()
        val consumer = consumerId()

        val assignment = withMediator {
            TouchConsumerNode(tenant, consumer, "node-a").execute()
        }

        assertThat(assignment.total).isEqualTo(Partition.TOTAL_PARTITIONS)
        assertThat(assignment.mine).hasSize(Partition.TOTAL_PARTITIONS)
        assertThat(assignment.mine.toSet()).isEqualTo((0 until Partition.TOTAL_PARTITIONS).toSet())
        assertThat(assignment.hash).isEqualTo("murmur3")
    }

    @Test
    fun `two nodes split the partitions, neither owning everything`() {
        val tenant = tenant()
        val consumer = consumerId()

        // First node arrives — it's alone, so it sees the full 64. (Touch returns
        // the assignment computed AT touch time; we don't pre-empt for nodes that
        // haven't checked in yet.)
        val a = withMediator { TouchConsumerNode(tenant, consumer, "node-a").execute() }
        assertThat(a.mine).hasSize(Partition.TOTAL_PARTITIONS)

        // Second node arrives — sees node-a in the active set, so the ring splits
        // partitions across both. node-b's slice is some strict subset of the 64.
        val b = withMediator { TouchConsumerNode(tenant, consumer, "node-b").execute() }
        assertThat(b.mine.size).isLessThan(Partition.TOTAL_PARTITIONS)
        assertThat(b.mine).isNotEmpty

        // Now re-touch node-a — it sees both itself and node-b as active and gets
        // the complement of b. Together they cover all partitions, no overlap.
        val aRefreshed = withMediator { TouchConsumerNode(tenant, consumer, "node-a").execute() }
        assertThat(aRefreshed.mine.size + b.mine.size).isEqualTo(Partition.TOTAL_PARTITIONS)
        assertThat(aRefreshed.mine.intersect(b.mine.toSet())).isEmpty()
    }

    @Test
    fun `idempotent - re-touching the same node returns the same assignment when no other nodes joined`() {
        val tenant = tenant()
        val consumer = consumerId()

        val first = withMediator { TouchConsumerNode(tenant, consumer, "node-a").execute() }
        val second = withMediator { TouchConsumerNode(tenant, consumer, "node-a").execute() }

        assertThat(second.mine).isEqualTo(first.mine)
    }

    @Test
    fun `assignment is per-(tenant, consumer) — two consumers in the same tenant don't compete`() {
        val tenant = tenant()
        val consumerA = consumerId()
        val consumerB = consumerId()

        val a1 = withMediator { TouchConsumerNode(tenant, consumerA, "node-1").execute() }
        val b1 = withMediator { TouchConsumerNode(tenant, consumerB, "node-1").execute() }

        // Each consumer's first node owns all partitions; they don't conflict
        // because each consumer has its own logical partition space.
        assertThat(a1.mine).hasSize(Partition.TOTAL_PARTITIONS)
        assertThat(b1.mine).hasSize(Partition.TOTAL_PARTITIONS)
    }

    @Test
    fun `same nodeId in different tenants is treated independently`() {
        val tenantA = tenant()
        val tenantB = tenant()
        val consumer = consumerId()

        val a = withMediator { TouchConsumerNode(tenantA, consumer, "node-shared").execute() }
        val b = withMediator { TouchConsumerNode(tenantB, consumer, "node-shared").execute() }

        // Each tenant's first call sees only itself.
        assertThat(a.mine).hasSize(Partition.TOTAL_PARTITIONS)
        assertThat(b.mine).hasSize(Partition.TOTAL_PARTITIONS)
    }
}
