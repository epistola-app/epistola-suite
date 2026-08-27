// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the installation-wide state of catalog publication as gauges.
 *
 * These describe one shared database, not one pod, so they follow the same rules as the other
 * `epistola.installation.*` gauges (see `InstallationStatsPublisher` and `docs/metrics.md`): every
 * replica runs the schedule, only the advisory-lock holder publishes a round, and non-holders
 * report `NaN` so Micrometer omits them and a naive `sum()` cannot multiply the series by the
 * replica count. `MetricsConfig.stripInstanceFromInstallationGauges` removes the `instance` tag so
 * the series survives a leadership change.
 *
 * The age gauge is the one that matters operationally: counts tell you how much work exists,
 * `..._oldest_active_age_seconds` tells you whether any of it is stuck. A tenant whose enrollment
 * lapsed keeps queueing releases that never move, and nothing else surfaces that.
 */
@Component
class ExchangeMetricsPublisher(
    private val jdbi: Jdbi,
    private val store: CatalogPublicationStore,
    private val credentials: ExchangeCredentialService,
    private val properties: ExchangeProperties,
    meterRegistry: MeterRegistry,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    private val publications: Map<CatalogPublicationStatus, AtomicReference<Double>> =
        CatalogPublicationStatus.entries.associateWith { AtomicReference(Double.NaN) }
    private val connections: Map<ExchangeConnectionStatus, AtomicReference<Double>> =
        ExchangeConnectionStatus.entries.associateWith { AtomicReference(Double.NaN) }
    private val oldestActiveAgeSeconds = AtomicReference(Double.NaN)

    init {
        publications.forEach { (status, value) ->
            Gauge.builder(PUBLICATIONS) { value.get() }
                .tag("status", status.name.lowercase())
                .description("Installation-wide count of catalog release publications in each state")
                .register(meterRegistry)
        }
        connections.forEach { (status, value) ->
            Gauge.builder(CONNECTIONS) { value.get() }
                .tag("status", status.name.lowercase())
                .description("Installation-wide count of Exchange tenant connections in each state")
                .register(meterRegistry)
        }
        Gauge.builder(OLDEST_ACTIVE) { oldestActiveAgeSeconds.get() }
            .description("Age of the oldest catalog publication that has not reached a terminal state")
            .register(meterRegistry)
    }

    @Bean
    fun exchangeMetricsScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
        enabled = properties.enabled,
    )

    override fun handle(task: ClusterScheduledTask) = publish()

    fun publish() {
        if (!properties.enabled) return
        jdbi.useTransaction<Exception> { handle ->
            val acquired = handle.createQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .bind("key", EXCHANGE_METRICS_LOCK_KEY).mapTo(Boolean::class.java).one()
            if (!acquired) {
                withhold()
                return@useTransaction
            }
            val publicationCounts = store.installationCountsByStatus()
            publications.forEach { (status, value) -> value.set((publicationCounts[status] ?: 0L).toDouble()) }
            val connectionCounts = credentials.installationCountsByStatus()
            connections.forEach { (status, value) -> value.set((connectionCounts[status] ?: 0L).toDouble()) }
            oldestActiveAgeSeconds.set(store.installationOldestActiveAgeSeconds())
            logger.debug("Published Exchange publication gauges: {}", publicationCounts)
        }
    }

    /** Another replica owns this round; expose nothing rather than a duplicate series. */
    private fun withhold() {
        publications.values.forEach { it.set(Double.NaN) }
        connections.values.forEach { it.set(Double.NaN) }
        oldestActiveAgeSeconds.set(Double.NaN)
    }

    private companion object {
        const val PUBLICATIONS = "epistola.installation.exchange_publications"
        const val CONNECTIONS = "epistola.installation.exchange_connections"
        const val OLDEST_ACTIVE = "epistola.installation.exchange_publication_oldest_active_age_seconds"

        /** Stable bigint key for pg_try_advisory_xact_lock, distinct from the other publishers. "EpExchM1". */
        const val EXCHANGE_METRICS_LOCK_KEY: Long = 0x4570_4578_6368_4D31L

        const val INTERVAL_MS = 60_000L

        const val TASK_KEY = "core.exchange-metrics"
        const val ROUTING_KEY = "system:core.exchange-metrics"
        const val TASK_TYPE = "core.exchange-metrics"
    }
}
