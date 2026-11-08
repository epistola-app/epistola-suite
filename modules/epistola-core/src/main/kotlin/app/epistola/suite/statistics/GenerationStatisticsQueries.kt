// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.statistics

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
