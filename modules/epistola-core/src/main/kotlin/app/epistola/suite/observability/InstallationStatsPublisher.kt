// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.observability

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes installation-wide entity counts as gauges
 * (`epistola.installation.<entity>`): how many tenants, templates, themes,
 * catalogs, stencils, fonts, environments this installation holds.
 *
 * These figures span the whole installation (one shared database), not a single
 * pod, so two things matter for a multi-replica fleet:
 *
 *  1. **Published once.** Every replica runs the schedule, but only the holder
 *     of a Postgres advisory lock computes and publishes the counts for that
 *     round (mirrors [app.epistola.suite.documents.cleanup.PartitionMaintenanceScheduler]).
 *     Non-holders withhold their gauges by reporting `NaN`, which Micrometer
 *     omits from export — so there is exactly one live series, never N copies
 *     that a naive `sum()` would multiply.
 *  2. **No `instance` tag.** A [MeterFilter] strips `instance` from
 *     `epistola.installation.*` (see `MetricsConfig`) so the series is stable
 *     across leader changes rather than churning by pod.
 *
 * Only bounded design-time entities are counted; high-volume runtime tables
 * (`documents`, `document_generation_requests`) are deliberately excluded —
 * their volume is tracked by the generation counters, and `count(*)` on a large
 * partitioned table every minute would not be free.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.metrics.installation-stats.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class InstallationStatsPublisher(
    private val jdbi: Jdbi,
    @Value("\${epistola.metrics.installation-stats.interval-ms:60000}")
    private val intervalMs: Long,
    meterRegistry: MeterRegistry,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    // NaN until this replica is the lock holder for a round — Micrometer omits
    // NaN-valued gauges from export, so non-leaders publish nothing.
    private val values: Map<Entity, AtomicReference<Double>> =
        ENTITIES.associateWith { AtomicReference(Double.NaN) }

    init {
        ENTITIES.forEach { entity ->
            Gauge.builder("epistola.installation.${entity.metric}") { values.getValue(entity).get() }
                .description("Installation-wide count of ${entity.metric}")
                .register(meterRegistry)
        }
    }

    @Bean
    fun installationStatsScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        publish()
    }

    fun publish() {
        jdbi.useTransaction<Exception> { handle ->
            val acquired = handle.createQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .bind("key", INSTALLATION_STATS_LOCK_KEY)
                .mapTo(Boolean::class.java)
                .one()
            if (!acquired) {
                // Another replica owns this round — withhold our gauges so we
                // don't expose a duplicate series.
                values.values.forEach { it.set(Double.NaN) }
                return@useTransaction
            }
            ENTITIES.forEach { entity ->
                val count = handle.createQuery("SELECT count(*) FROM ${entity.table}")
                    .mapTo(Long::class.java)
                    .one()
                values.getValue(entity).set(count.toDouble())
            }
            logger.debug("Published installation stats: {}", values.mapKeys { it.key.metric }.mapValues { it.value.get() })
        }
    }

    /** Bounded design-time entity tables. `table` is a hardcoded literal — never user input. */
    internal enum class Entity(val metric: String, val table: String) {
        TENANTS("tenants", "tenants"),
        TEMPLATES("templates", "document_templates"),
        THEMES("themes", "themes"),
        CATALOGS("catalogs", "catalogs"),
        STENCILS("stencils", "stencils"),
        FONTS("fonts", "fonts"),
        ENVIRONMENTS("environments", "environments"),
    }

    private companion object {
        val ENTITIES = Entity.entries

        // Stable bigint key for pg_try_advisory_xact_lock, distinct from the
        // partition-maintenance key. "EpInstS1".
        private const val INSTALLATION_STATS_LOCK_KEY: Long = 0x4570_496E_7374_5331L

        const val TASK_KEY = "core.installation-stats"
        const val ROUTING_KEY = "system:core.installation-stats"
        const val TASK_TYPE = "core.installation-stats"
    }
}
