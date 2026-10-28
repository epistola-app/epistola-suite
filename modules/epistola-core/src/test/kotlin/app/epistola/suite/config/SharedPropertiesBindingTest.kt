// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.documents.JobPollingProperties
import app.epistola.suite.documents.cleanup.CorePartitionedTables
import app.epistola.suite.documents.cleanup.PartitionProperties
import app.epistola.suite.generation.collect.CollectProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * The settings several components read are bound once, by the names operators already use. With
 * `@Value` on each reader a misspelt key fell back to its default without a word; here a name that
 * does not bind fails.
 */
class SharedPropertiesBindingTest {

    private fun binder(vararg values: Pair<String, String>) = Binder(MapConfigurationPropertySource(values.toMap()))

    @Test
    fun `the partition settings bind by their names, and every partitioned table takes its retention from them`() {
        val partitions = binder(
            "epistola.partitions.enabled" to "false",
            "epistola.partitions.retention-months" to "5",
            "epistola.partitions.generation-results-retention-months" to "2",
            "epistola.partitions.event-log-retention-months" to "9",
            "epistola.partitions.maintenance-cron" to "0 0 4 * * ?",
        ).bind("epistola.partitions", PartitionProperties::class.java).get()

        assertThat(partitions).isEqualTo(PartitionProperties(false, 5, 2, 9, "0 0 4 * * ?"))
        assertThat(CorePartitionedTables(partitions).partitionedTables().associate { it.tableName to it.retentionMonths })
            .containsEntry("documents", 5)
            .containsEntry("document_content", 5)
            .containsEntry("document_generation_requests", 5)
            .containsEntry("generation_results", 2)
            .containsEntry("event_log", 9)
    }

    @Test
    fun `the collect settings bind by their names`() {
        val collect = binder(
            "epistola.collect.idle-timeout-ms" to "30000",
            "epistola.collect.show-stale-window-hours" to "3",
            "epistola.collect.stale-node-retention-hours" to "48",
            "epistola.collect.reaper.enabled" to "false",
            "epistola.collect.reaper.cron" to "0 0 5 * * *",
        ).bind("epistola.collect", CollectProperties::class.java).get()

        assertThat(collect).isEqualTo(CollectProperties(30_000, 3, 48, CollectProperties.Reaper(false, "0 0 5 * * *")))
    }

    @Test
    fun `the stale-job timeout binds where the rest of job polling does`() {
        val polling = binder("epistola.generation.polling.stale-timeout-minutes" to "25")
            .bind("epistola.generation.polling", JobPollingProperties::class.java).get()

        assertThat(polling.staleTimeoutMinutes).isEqualTo(25)
    }

    @Test
    fun `unset, they keep the defaults the readers had`() {
        assertThat(PartitionProperties()).isEqualTo(PartitionProperties(true, 3, 1, 6, "0 0 2 * * ?"))
        assertThat(CollectProperties()).isEqualTo(CollectProperties(60_000, 1, 24, CollectProperties.Reaper(true, "0 0 3 * * *")))
        assertThat(JobPollingProperties().staleTimeoutMinutes).isEqualTo(10)
    }
}
