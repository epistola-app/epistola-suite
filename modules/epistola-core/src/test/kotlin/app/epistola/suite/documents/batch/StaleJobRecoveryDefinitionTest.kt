// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/**
 * Document recovery must never depend on a single elected node staying healthy: if a
 * node wedges while holding the recovery task's lease, stale IN_PROGRESS documents would
 * be orphaned forever (#723). The task therefore runs on EACH capable node — the
 * idempotent sweep is safe to run everywhere, and any healthy node recovers the fleet's
 * stale jobs. This test guards that invariant against a silent revert to SINGLE_OWNER.
 */
class StaleJobRecoveryDefinitionTest {

    @Test
    fun `stale-job recovery runs on every capable node, not a single owner`() {
        val recovery = StaleJobRecovery(
            jdbi = mock(Jdbi::class.java),
            meterRegistry = SimpleMeterRegistry(),
            staleTimeoutMinutes = 10,
        )

        val definition = recovery.staleJobRecoveryScheduledTaskDefinition()

        assertThat(definition.executionScope)
            .`as`("stale-job recovery must be EACH_CAPABLE_NODE so no single wedged owner can orphan documents")
            .isEqualTo(ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE)
    }
}
