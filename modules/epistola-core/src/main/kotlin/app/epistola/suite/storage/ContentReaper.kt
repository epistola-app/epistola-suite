// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * What still holds an `asset_content` blob, for a row aliased `ac`.
 *
 * One definition for the sweep and for the gauge: two copies of a reachability rule is how a blob
 * comes to be deleted by one and counted as present by the other.
 *
 * Two kinds of holder. A **live asset row** — scope derived from its `sensitive` flag exactly as at
 * write time, so a global blob survives while any asset references it and a sensitive one only
 * while its own tenant does. And a **revision**, which is the content a release retained:
 * `revision_binaries` names the bytes a released resource needs, and dropping that resource from
 * the working copy must not take them with it (ADR 0026). The foreign key from `revision_binaries`
 * says the same thing, so a miss here fails loudly rather than losing bytes.
 */
private const val BLOB_IS_HELD = """
    EXISTS (
        SELECT 1 FROM assets a
        WHERE a.content_hash = ac.content_hash
          AND (CASE WHEN a.sensitive THEN a.tenant_key::text ELSE 'global' END) = ac.scope
    )
    OR EXISTS (
        SELECT 1 FROM revision_binaries rb
        WHERE rb.content_hash = ac.content_hash AND rb.scope = ac.scope
    )
"""

/**
 * Reclaims unreferenced blob storage and drives backend-specific document retention
 * (issue #738). A single-owner, daily cluster task that:
 *
 *  1. **Mark-and-sweeps asset blobs** — deletes `asset_content` rows that nothing holds
 *     ([BLOB_IS_HELD]: no live `assets` row and no retained revision). This is how a
 *     deleted or re-pointed asset's bytes are actually reclaimed, since `DeleteAsset` no
 *     longer deletes blobs. A grace window skips very recently written blobs so an
 *     in-flight upload (blob written, `assets` row not yet inserted) is never swept.
 *  2. **Drives [ContentRetentionMaintainer]s** — the filesystem document backend's age
 *     sweep (PostgreSQL reclaims via partition drops, S3 via its lifecycle rule, so both
 *     contribute a no-op maintainer).
 *  3. **Publishes a gauge** — `epistola.storage.orphaned_blobs{namespace=asset}` so a
 *     leak (or a regression in the reclaim path) can't grow silently.
 *
 * All work is idempotent (set-based `DELETE … WHERE NOT EXISTS`, put-if-absent uploads),
 * so a re-run of a wedged single-owner occurrence is safe — no advisory lock needed.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.storage.reaper.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ContentReaper(
    private val jdbi: Jdbi,
    private val meterRegistry: MeterRegistry,
    private val maintainers: List<ContentRetentionMaintainer>,
    @org.springframework.beans.factory.annotation.Value("\${epistola.partitions.retention-months:3}")
    private val retentionMonths: Int,
    @org.springframework.beans.factory.annotation.Value("\${epistola.storage.reaper.asset-grace-minutes:60}")
    private val assetGraceMinutes: Long,
    @org.springframework.beans.factory.annotation.Value("\${epistola.storage.reaper.cron:0 30 3 * * ?}")
    private val reaperCron: String,
) : ClusterScheduledTaskHandler {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    private val orphanedAssetBlobs = AtomicLong(0)

    init {
        meterRegistry.gauge(
            "epistola.storage.orphaned_blobs",
            listOf(io.micrometer.core.instrument.Tag.of("namespace", "asset")),
            orphanedAssetBlobs,
        ) { it.get().toDouble() }
    }

    @Bean
    fun contentReaperScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(reaperCron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        reap()
    }

    fun reap() {
        meterRegistry.recordScheduledTask("content-reaper") {
            val swept = sweepUnreferencedAssetBlobs()
            if (swept > 0) logger.info("Reaped {} unreferenced asset blob(s)", swept)

            maintainers.forEach { maintainer ->
                try {
                    maintainer.reclaim(retentionMonths)
                } catch (e: Exception) {
                    logger.error("Content retention maintainer {} failed: {}", maintainer.javaClass.simpleName, e.message, e)
                }
            }

            orphanedAssetBlobs.set(countUnreferencedAssetBlobs())
        }
    }

    /** Delete blobs older than the grace window that nothing holds — see [BLOB_IS_HELD]. */
    private fun sweepUnreferencedAssetBlobs(): Int {
        val cutoff = EpistolaClock.offsetDateTime().minusMinutes(assetGraceMinutes)
        return jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM asset_content ac
                WHERE ac.created_at < :cutoff
                  AND NOT ($BLOB_IS_HELD)
                """,
            )
                .bind("cutoff", cutoff)
                .execute()
        }
    }

    /** Count of unreferenced asset blobs still present (bounded small table). */
    private fun countUnreferencedAssetBlobs(): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM asset_content ac WHERE NOT ($BLOB_IS_HELD)")
            .mapTo(Long::class.java)
            .one()
    }

    companion object {
        const val TASK_KEY = "core.content-reaper"
        const val ROUTING_KEY = "system:core.content-reaper"
        const val TASK_TYPE = "core.content-reaper"
    }
}
