// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.releaseAt
import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.time.EpistolaClock
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * How much each deployed catalog version is used, so the people running an environment can see which
 * versions nobody asks for any more before undeploying them (`environment_catalog_deployments
 * .documents_requested`, `.last_used_at`).
 *
 * A request is counted when it is accepted through an environment for a version it serves. Counting
 * in the database per request would have every request for a popular version update the same row;
 * each node tallies in memory instead and adds its tallies to the deployments about once a minute,
 * and when it stops. A tally for a version undeployed meanwhile updates nothing: the count belongs
 * to the deployment and goes with it.
 */
@Component
class DeploymentUsage(
    private val jdbi: Jdbi,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    private data class Key(val environmentKey: EnvironmentKey, val catalog: CatalogId, val version: String) {
        val tenantKey: TenantKey get() = catalog.tenantKey
    }

    private class Tally {
        val count = AtomicLong()
        val last = AtomicReference<Instant>(Instant.EPOCH)
    }

    private val tallies = ConcurrentHashMap<Key, Tally>()

    /** Counts [requests] accepted through [environmentKey] for [version] of [catalog]. */
    fun record(environmentKey: EnvironmentKey, catalog: CatalogId, version: String, requests: Int = 1) {
        val now = EpistolaClock.instant()
        val tally = tallies.computeIfAbsent(Key(environmentKey, catalog, version)) { Tally() }
        tally.count.addAndGet(requests.toLong())
        tally.last.accumulateAndGet(now) { a, b -> maxOf(a, b) }
    }

    @Bean
    fun deploymentUsageScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS),
        // Every node holds its own tallies.
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) = flush()

    @PreDestroy
    fun flushOnShutdown() = runCatching { flush() }.onFailure { logger.warn("Could not record deployment usage on shutdown: {}", it.message) }

    /** Adds this node's tallies to the deployments, and starts new ones. */
    fun flush() {
        val taken = tallies.keys.toList().mapNotNull { key -> tallies.remove(key)?.let { key to it } }
        if (taken.isEmpty()) return
        try {
            write(taken)
        } catch (e: Exception) {
            // Kept for the next flush rather than lost.
            taken.forEach { (key, tally) ->
                val kept = tallies.computeIfAbsent(key) { Tally() }
                kept.count.addAndGet(tally.count.get())
                kept.last.accumulateAndGet(tally.last.get()) { a, b -> maxOf(a, b) }
            }
            throw e
        }
    }

    private fun write(taken: List<Pair<Key, Tally>>) {
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                UPDATE environment_catalog_deployments
                SET documents_requested = documents_requested + :count,
                    last_used_at = GREATEST(last_used_at, :last)
                WHERE tenant_key = :t AND environment_key = :e AND target_version_id = ${releaseAt("t", "c", "v")}
                """,
            )
            taken.forEach { (key, tally) ->
                batch.bind("count", tally.count.get())
                    .bind("last", tally.last.get().atOffset(ZoneOffset.UTC))
                    .bind("t", key.tenantKey)
                    .bind("e", key.environmentKey)
                    .bindCatalog("c", key.catalog)
                    .bind("v", key.version)
                    .add()
            }
            batch.execute()
        }
    }

    companion object {
        const val TASK_KEY = "core.deployment-usage"
        const val ROUTING_KEY = "system:core.deployment-usage"
        const val TASK_TYPE = "core.deployment-usage"
        const val INTERVAL_MS = 60_000L
    }
}
