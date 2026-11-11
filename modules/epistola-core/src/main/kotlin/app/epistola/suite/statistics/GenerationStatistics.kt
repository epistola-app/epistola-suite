// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.statistics

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.DispatchOutcome
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryListener
import app.epistola.suite.time.EpistolaClock
import jakarta.annotation.PreDestroy
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Generation statistics per tenant (`epistola.statistics`): documents generated and failed, and
 * previews rendered, per hour for [hourlyRetention], then per day, kept.
 *
 * @property rollupInterval how often one node counts the new generation results.
 * @property settle results younger than this wait for the next round: their sequence numbers may still
 *   commit out of order, and counting one later than a higher one already counted would skip it.
 * @property chunkSize results counted per transaction; a round counts at most [maxChunks] of them.
 * @property previewFlushInterval how often each node writes the previews it counted in memory.
 */
@ConfigurationProperties(prefix = "epistola.statistics")
data class StatisticsProperties(
    val enabled: Boolean = true,
    val hourlyRetention: Duration = Duration.ofDays(7),
    val rollupInterval: Duration = Duration.ofMinutes(2),
    val settle: Duration = Duration.ofMinutes(2),
    val chunkSize: Int = 10_000,
    val maxChunks: Int = 20,
    val previewFlushInterval: Duration = Duration.ofMinutes(1),
) {
    init {
        require(hourlyRetention >= Duration.ofDays(1)) { "epistola.statistics.hourly-retention must be at least a day" }
        require(!settle.isNegative) { "epistola.statistics.settle must not be negative" }
        require(rollupInterval.isPositive && previewFlushInterval.isPositive) { "epistola.statistics.rollup-interval and preview-flush-interval must be positive" }
        require(chunkSize > 0 && maxChunks > 0) { "epistola.statistics.chunk-size and max-chunks must be positive" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatisticsProperties::class)
class StatisticsConfiguration

/**
 * Counts generation results into `generation_stats_hourly` in the background, so generating a
 * document does no extra work for it. Each `generation_results` row is read once: a round takes the
 * rows after the stored position, in chunks, adds them per tenant and hour, and moves the position in
 * the same transaction. Deleting a document does not touch its result, so it is counted all the same.
 * The statistics lag the results by [StatisticsProperties.settle] plus a round, then agree with them.
 *
 * Every round also folds hours older than [StatisticsProperties.hourlyRetention] into days.
 */
@Component
class GenerationStatisticsRollup(
    private val jdbi: Jdbi,
    private val properties: StatisticsProperties,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun generationStatisticsScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.rollupInterval.toMillis()),
        enabled = properties.enabled,
    )

    override fun handle(task: ClusterScheduledTask) {
        rollUp()
        fold()
    }

    /** Counts the results that settled since the last round; the number counted. */
    fun rollUp(): Int {
        var counted = 0
        repeat(properties.maxChunks) {
            val chunk = jdbi.inTransaction<Int, Exception> { handle -> chunk(handle) }
            counted += chunk
            if (chunk < properties.chunkSize) return counted
        }
        return counted
    }

    private fun chunk(handle: Handle): Int {
        val position = handle.createQuery(
            "SELECT (value->>'sequence')::bigint AS sequence, (value->>'createdAt')::timestamptz AS created_at FROM app_metadata WHERE key = :key FOR UPDATE",
        )
            .bind("key", POSITION_KEY)
            .map { rs, _ -> rs.getLong("sequence") to rs.getObject("created_at", OffsetDateTime::class.java) }
            .findOne()
            .orElse(null)
        val after = position?.first ?: 0L
        // A lower bound on created_at lets PostgreSQL skip old partitions; sequence order and creation
        // order agree within far less than the margin. The chunk stops below the first result that has
        // not settled yet: created_at is its transaction's start and the sequence is taken at insert, so a
        // younger result can hold a lower sequence than an older one, and moving past it would skip it.
        val since = position?.second?.minus(PRUNING_MARGIN) ?: OffsetDateTime.parse("1970-01-01T00:00:00Z")
        val summary = handle.createQuery(
            """
            WITH chunk AS (
                SELECT sequence, created_at, tenant_key, status, completed_at
                FROM generation_results
                WHERE sequence > :after AND created_at >= :since AND created_at < NOW() - CAST(:settle AS INTERVAL)
                  AND sequence < COALESCE(
                      (SELECT MIN(y.sequence) FROM generation_results y
                       WHERE y.sequence > :after AND y.created_at >= NOW() - CAST(:settle AS INTERVAL)),
                      9223372036854775807)
                ORDER BY sequence
                LIMIT :limit
            ),
            counted AS (
                INSERT INTO generation_stats_hourly (tenant_key, hour, completed, failed)
                SELECT tenant_key, date_trunc('hour', completed_at),
                       COUNT(*) FILTER (WHERE status = 'COMPLETED'), COUNT(*) FILTER (WHERE status = 'FAILED')
                FROM chunk
                GROUP BY tenant_key, date_trunc('hour', completed_at)
                ON CONFLICT (tenant_key, hour) DO UPDATE SET
                    completed = generation_stats_hourly.completed + EXCLUDED.completed,
                    failed = generation_stats_hourly.failed + EXCLUDED.failed
            )
            SELECT COUNT(*) AS rows, MAX(sequence) AS last_sequence, MAX(created_at) AS last_created_at FROM chunk
            """,
        )
            .bind("after", after)
            .bind("since", since)
            .bind("settle", "${properties.settle.toSeconds()} seconds")
            .bind("limit", properties.chunkSize)
            .map { rs, _ -> Triple(rs.getInt("rows"), rs.getLong("last_sequence"), rs.getObject("last_created_at", OffsetDateTime::class.java)) }
            .one()
        val (rows, lastSequence, lastCreatedAt) = summary
        val complete = rows < properties.chunkSize
        // The new position; createdAt and completeUntil keep their value when this chunk has none.
        handle.createUpdate(
            """
            INSERT INTO app_metadata (key, value, updated_at)
            VALUES (:key, jsonb_strip_nulls(jsonb_build_object(
                'sequence', CAST(:sequence AS BIGINT),
                'createdAt', CAST(:createdAt AS TIMESTAMPTZ),
                'completeUntil', CASE WHEN :complete THEN NOW() - CAST(:settle AS INTERVAL) END)), NOW())
            ON CONFLICT (key) DO UPDATE SET value = app_metadata.value || EXCLUDED.value, updated_at = NOW()
            """,
        )
            .bind("key", POSITION_KEY)
            .bind("sequence", if (rows > 0) lastSequence else after)
            .bind("createdAt", if (rows > 0) lastCreatedAt else position?.second)
            .bind("complete", complete)
            .bind("settle", "${properties.settle.toSeconds()} seconds")
            .execute()
        if (rows > 0) log.debug("Generation statistics counted results results={} sequence={}", rows, lastSequence)
        return rows
    }

    /** Folds whole UTC days of hours older than the hourly retention into days. */
    fun fold(now: Instant = EpistolaClock.instant()) {
        val cutoff = now.minus(properties.hourlyRetention).truncatedTo(ChronoUnit.DAYS).atOffset(ZoneOffset.UTC)
        jdbi.useTransaction<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO generation_stats_daily (tenant_key, day, completed, failed, previews)
                SELECT tenant_key, (hour AT TIME ZONE 'UTC')::date, SUM(completed), SUM(failed), SUM(previews)
                FROM generation_stats_hourly WHERE hour < :cutoff
                GROUP BY tenant_key, (hour AT TIME ZONE 'UTC')::date
                ON CONFLICT (tenant_key, day) DO UPDATE SET
                    completed = generation_stats_daily.completed + EXCLUDED.completed,
                    failed = generation_stats_daily.failed + EXCLUDED.failed,
                    previews = generation_stats_daily.previews + EXCLUDED.previews
                """,
            ).bind("cutoff", cutoff).execute()
            handle.createUpdate("DELETE FROM generation_stats_hourly WHERE hour < :cutoff").bind("cutoff", cutoff).execute()
        }
    }

    companion object {
        const val TASK_KEY = "core.generation-statistics"
        const val ROUTING_KEY = "system:core.generation-statistics"
        const val TASK_TYPE = "core.generation-statistics"
        private val PRUNING_MARGIN: Duration = Duration.ofHours(1)

        /** The `app_metadata` entry of where the roll-up stands. */
        const val POSITION_KEY = "statistics.generation-position"
    }
}

/**
 * Counts previews in memory as they are rendered and writes them once a minute per node, so a preview
 * costs an in-memory add. A [PreviewDocument] (REST API, MCP, environments) counts, and so does a
 * [PreviewVariant] asked for directly (the editor); one a [PreviewDocument] renders for itself is part
 * of that one. A node that stops abruptly loses at most its last minute: previews are not billed.
 */
@Component
class PreviewStatisticsCounter(
    private val jdbi: Jdbi,
    private val properties: StatisticsProperties,
) : QueryListener,
    ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Previews per tenant and hour since the last flush; added to and taken out per key, atomically. */
    private val pending = ConcurrentHashMap<Pair<String, Instant>, Long>()
    override val taskType: String = TASK_TYPE

    @Bean
    fun previewStatisticsFlushTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.previewFlushInterval.toMillis()),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
        enabled = properties.enabled,
    )

    override fun onQuery(query: Query<*>, outcome: DispatchOutcome, error: Throwable?) {
        if (!properties.enabled || outcome != DispatchOutcome.SUCCESS) return
        val tenant = when (query) {
            is PreviewDocument -> query.tenantKey
            is PreviewVariant -> query.tenantKey.takeUnless { query.partOfPreviewDocument }
            else -> null
        } ?: return
        val key = tenant.value to EpistolaClock.instant().truncatedTo(ChronoUnit.HOURS)
        // Bounded: a key per tenant and hour since the last flush; beyond that, previews go uncounted.
        if (pending.size >= MAX_PENDING && !pending.containsKey(key)) return
        pending.merge(key, 1L, Long::plus)
    }

    override fun handle(task: ClusterScheduledTask) = flush()

    /** Writes what was counted since the last flush. */
    @PreDestroy
    fun flush() {
        if (!properties.enabled || pending.isEmpty()) return
        // remove() takes a key's count atomically: a preview counted meanwhile starts a new one.
        val taken = pending.keys.toList().mapNotNull { key -> pending.remove(key)?.takeIf { it > 0 }?.let { key to it } }
        if (taken.isEmpty()) return
        try {
            jdbi.useTransaction<Exception> { handle ->
                val batch = handle.prepareBatch(
                    """
                    INSERT INTO generation_stats_hourly (tenant_key, hour, previews) VALUES (:tenant, :hour, :previews)
                    ON CONFLICT (tenant_key, hour) DO UPDATE SET previews = generation_stats_hourly.previews + EXCLUDED.previews
                    """,
                )
                taken.forEach { (key, count) -> batch.bind("tenant", key.first).bind("hour", key.second.atOffset(ZoneOffset.UTC)).bind("previews", count).add() }
                batch.execute()
            }
        } catch (e: Exception) {
            // Kept for the next flush rather than lost.
            taken.forEach { (key, count) -> pending.merge(key, count, Long::plus) }
            log.warn("Preview statistics could not be written; kept for the next flush reason=\"{}\"", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val TASK_KEY = "core.preview-statistics-flush"
        const val ROUTING_KEY = "system:core.preview-statistics-flush"
        const val TASK_TYPE = "core.preview-statistics-flush"
        private const val MAX_PENDING = 100_000
    }
}
