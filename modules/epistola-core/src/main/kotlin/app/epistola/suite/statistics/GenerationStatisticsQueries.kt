// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.statistics

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** How [GetGenerationStatistics] groups its figures. */
enum class StatisticsPeriod { DAY, MONTH }

/** One period's figures: [start] is its first day (UTC). */
data class GenerationPeriod(val start: LocalDate, val completed: Long, val failed: Long, val previews: Long)

/**
 * A tenant's figures per period, oldest first; periods without anything are left out.
 *
 * @property completeUntil the statistics hold every result created before this; later ones follow
 *   within minutes. Null until the first round.
 */
data class GenerationStatistics(val tenantKey: TenantKey, val period: StatisticsPeriod, val periods: List<GenerationPeriod>, val completeUntil: Instant?) {
    val completed: Long get() = periods.sumOf { it.completed }
    val failed: Long get() = periods.sumOf { it.failed }
    val previews: Long get() = periods.sumOf { it.previews }
}

/** A tenant's documents generated and failed, and previews rendered, from [from] to [to] (UTC days, both included). */
data class GetGenerationStatistics(
    override val tenantKey: TenantKey,
    val from: LocalDate,
    val to: LocalDate,
    val period: StatisticsPeriod = StatisticsPeriod.DAY,
) : Query<GenerationStatistics>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW

    init {
        validate("to", !to.isBefore(from)) { "The end cannot be before the start" }
        validate("from", ChronoUnit.DAYS.between(from, to) <= MAX_DAYS) { "At most $MAX_DAYS days at once" }
    }

    companion object {
        const val MAX_DAYS = 800L
    }
}

@Component
class GetGenerationStatisticsHandler(private val jdbi: Jdbi) : QueryHandler<GetGenerationStatistics, GenerationStatistics> {
    override fun handle(query: GetGenerationStatistics): GenerationStatistics = jdbi.withHandle<GenerationStatistics, Exception> { handle ->
        val unit = if (query.period == StatisticsPeriod.MONTH) "month" else "day"
        val periods = handle.createQuery(
            """
            SELECT date_trunc('$unit', day::timestamp)::date AS start, SUM(completed) AS completed, SUM(failed) AS failed, SUM(previews) AS previews
            FROM (
                SELECT day, completed, failed, previews FROM generation_stats_daily
                WHERE tenant_key = :tenant AND day BETWEEN :from AND :to
                UNION ALL
                SELECT (hour AT TIME ZONE 'UTC')::date, completed, failed, previews FROM generation_stats_hourly
                WHERE tenant_key = :tenant AND hour >= :fromInstant AND hour < :untilInstant
            ) figures
            GROUP BY 1
            ORDER BY 1
            """,
        )
            .bind("tenant", query.tenantKey.value)
            .bind("from", query.from)
            .bind("to", query.to)
            .bind("fromInstant", query.from.atStartOfDay().atOffset(ZoneOffset.UTC))
            .bind("untilInstant", query.to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC))
            .map { rs, _ -> GenerationPeriod(rs.getObject("start", LocalDate::class.java), rs.getLong("completed"), rs.getLong("failed"), rs.getLong("previews")) }
            .list()
        GenerationStatistics(query.tenantKey, query.period, periods, completeUntil(handle))
    }
}

private fun completeUntil(handle: org.jdbi.v3.core.Handle): Instant? = handle.createQuery(
    "SELECT (value->>'completeUntil')::timestamptz AS complete_until FROM app_metadata WHERE key = :key",
)
    .bind("key", GenerationStatisticsRollup.POSITION_KEY)
    .map { rs, _ -> rs.getObject("complete_until", OffsetDateTime::class.java)?.toInstant() }
    .findOne()
    .orElse(null)

/** One tenant's reading at the end of an hour: its figures for the hour and its running totals. */
data class GenerationReading(
    val tenantKey: TenantKey,
    val completed: Long,
    val previews: Long,
    val completedTotal: Long,
    val previewsTotal: Long,
)

/**
 * Every tenant's reading at the end of [hour] (its start), for usage reporting (ADR 0052 §6).
 *
 * @property complete the statistics hold every result created before the end of the hour.
 */
data class GenerationReadings(val hour: Instant, val readings: List<GenerationReading>, val complete: Boolean)

/**
 * Readings of every tenant that generated or previewed anything, at the end of [hour]: totals are
 * every day kept plus every hour up to and including [hour], so [hour] must still be kept by the hour
 * (`epistola.statistics.hourly-retention`).
 *
 * `SystemInternal`: the usage reporter asks it for every tenant at once, with no user behind it.
 */
data class GetGenerationReadings(val hour: Instant) :
    Query<GenerationReadings>,
    SystemInternal

@Component
class GetGenerationReadingsHandler(private val jdbi: Jdbi) : QueryHandler<GetGenerationReadings, GenerationReadings> {
    override fun handle(query: GetGenerationReadings): GenerationReadings = jdbi.withHandle<GenerationReadings, Exception> { handle ->
        val at = query.hour.truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC)
        val readings = handle.createQuery(
            """
            SELECT tenant_key,
                   COALESCE(SUM(completed) FILTER (WHERE hour = :hour), 0) AS hour_completed,
                   COALESCE(SUM(previews) FILTER (WHERE hour = :hour), 0) AS hour_previews,
                   SUM(completed) AS completed, SUM(previews) AS previews
            FROM (
                SELECT tenant_key, NULL::timestamptz AS hour, completed, previews FROM generation_stats_daily
                UNION ALL
                SELECT tenant_key, hour, completed, previews FROM generation_stats_hourly WHERE hour <= :hour
            ) figures
            GROUP BY tenant_key
            HAVING SUM(completed) > 0 OR SUM(previews) > 0
            ORDER BY tenant_key
            """,
        )
            .bind("hour", at)
            .map { rs, _ ->
                GenerationReading(TenantKey.of(rs.getString("tenant_key")), rs.getLong("hour_completed"), rs.getLong("hour_previews"), rs.getLong("completed"), rs.getLong("previews"))
            }
            .list()
        val completeUntil = completeUntil(handle)
        GenerationReadings(query.hour, readings, completeUntil != null && !completeUntil.isBefore(at.plusHours(1).toInstant()))
    }
}
