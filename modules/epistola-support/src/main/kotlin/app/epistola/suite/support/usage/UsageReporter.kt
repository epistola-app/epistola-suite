// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.usage

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.query
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.statistics.GenerationReadings
import app.epistola.suite.statistics.GetGenerationReadings
import app.epistola.suite.statistics.StatisticsProperties
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.isInsufficientScope
import app.epistola.suite.support.connection.isSupportUnavailable
import app.epistola.suite.support.connection.lastContactFailed
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.support.sharing.SharingRestrictions
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What one round of [UsageReporter] did. */
data class UsageReportRound(val sent: List<Instant>, val stoppedBecause: String? = null)

/**
 * Reports usage for billing (ADR 0052 §6) every hour, at the same moment within the first ten minutes
 * past the hour for this installation ([reportMoment]: chosen at random once and kept, so installations
 * spread over those minutes). Each finished UTC hour is sent once, with a reading for every tenant that shares usage (all of
 * them while usage is Required, none while it is not allowed): its figures for the hour and its running
 * totals, from core's generation statistics, once they hold the whole hour. Epistola bills from the totals, so an hour that is never sent loses nothing; hours missed
 * while the installation was down are sent when it is back, as far back as `epistola.support.usage.backlog`
 * ([backlog]). Which hours went out is kept only as long as a round can look back ([KEPT_BEYOND_BACKLOG] more).
 *
 * One node reports at a time (a database advisory lock); the service replaces an hour's report anyway.
 */
@Component
class UsageReporter(
    /** Epistola support's connection, as every module injects it. */
    private val discovered: SupportConnection,
    private val registry: ObjectProvider<SupportConnectionRegistry>,
    private val jdbi: Jdbi,
    private val references: TenantReferences,
    private val objectMapper: ObjectMapper,
    private val metadata: AppMetadataService,
    private val mediator: Mediator,
    private val statistics: StatisticsProperties,
    private val properties: SupportProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var moment: Duration? = null

    /** One reporter per connection usage goes to, kept while it does: each its own back-off, position and lock (ADR 0053 step 3). */
    private val reporters = java.util.concurrent.ConcurrentHashMap<UUID, ConnectionReporter>()

    private fun reporterFor(connection: SupportConnection): ConnectionReporter = reporters.compute(connection.id) { _, existing ->
        existing?.takeIf { it.connection === connection } ?: ConnectionReporter(connection)
    }!!

    /** Every connection usage may go to: Epistola support's, and every other one the installation selected usage for. */
    private fun connections(): List<SupportConnection> = listOf(discovered) +
        registry.ifAvailable?.tokenConnections().orEmpty().filter { it.isSelected(SupportScope.USAGE) }

    /**
     * How long after the start of every hour this installation reports: a moment within the first
     * [MOMENT_WINDOW_MINUTES] minutes, chosen once at random and kept, so the past hour arrives soon and
     * installations spread over those minutes. A stored moment outside the window is chosen again.
     */
    fun reportMoment(): Duration = moment ?: synchronized(this) {
        moment ?: run {
            val stored = metadata.get(MOMENT_KEY)?.let { Duration.ofMinutes(it.get("minute")?.asLong() ?: -1).plusSeconds(it.get("second")?.asLong() ?: 0) }
            val kept = stored?.takeIf { !it.isNegative && it < Duration.ofMinutes(MOMENT_WINDOW_MINUTES) } ?: run {
                val random = SecureRandom()
                val chosen = mapOf("minute" to random.nextInt(MOMENT_WINDOW_MINUTES.toInt()), "second" to random.nextInt(60))
                if (stored == null) metadata.setIfAbsent(MOMENT_KEY, chosen) else metadata.setAs(MOMENT_KEY, chosen)
                metadata.get(MOMENT_KEY).let { Duration.ofMinutes(it?.get("minute")?.asLong() ?: 0).plusSeconds(it?.get("second")?.asLong() ?: 0) }
            }
            kept.also { moment = it }
        }
    }

    /**
     * Reports usage to one support connection (ADR 0053 step 3): what it has been sent, its back-off,
     * where it started and its lock are its own. Epistola support's keeps the keys it always had.
     */
    private inner class ConnectionReporter(val connection: SupportConnection) {
        /** Where this connection's reporting started, in `app_metadata`. */
        private val sinceKey = "$SINCE_KEY.${connection.id}"

        /** The advisory lock that lets one node report to this connection at a time. */
        private val lockKey = LOCK_KEY xor connection.id.leastSignificantBits

        /** After a round that could not send everything, the next one waits until then. */
        @Volatile private var notBefore: Instant = Instant.MIN

        /** Whether an hour waits to be reported to this connection; run by [UsageReporter.tick]. */
        fun tick(now: Instant) {
            if (now.isBefore(notBefore) || !waiting(now)) return
            val round = runCatching { report() }.onFailure { log.warn("Usage report round failed: {}", it.javaClass.simpleName) }.getOrNull()
            notBefore = when {
                round == null -> now.plus(RETRY_AFTER)
                round.stoppedBecause == STATISTICS_BEHIND -> now.plus(Duration.ofMinutes(1))
                round.stoppedBecause != null -> now.plus(RETRY_AFTER)
                else -> Instant.MIN
            }
        }

        /** The previous hour has not been reported yet. */
        private fun waiting(now: Instant): Boolean {
            val previous = now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC)
            return jdbi.withHandle<Boolean, Exception> { handle ->
                handle.createQuery("SELECT COUNT(*) FROM support_usage_reports WHERE connection_id = :connection AND hour = :hour")
                    .bind("connection", connection.id).bind("hour", previous).mapTo(Int::class.java).one() == 0
            }
        }

        /**
         * Nothing may be reported now (not granted, or not allowed): the hours until now are never sent,
         * also once it may again. They were not shared while they happened; the running totals of the
         * first report after carry what they held.
         */
        private fun reportNothingBefore(now: Instant) {
            metadata.setAs(sinceKey, mapOf("hour" to now.truncatedTo(ChronoUnit.HOURS).toString()))
        }

        /** The first hour this installation reports: the hour it first tried, kept, so a new installation does not send empty days. */
        private fun reportingSince(now: Instant): Instant {
            metadata.setIfAbsent(sinceKey, mapOf("hour" to now.truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS).toString()))
            return metadata.get(sinceKey)?.get("hour")?.asString()?.let(Instant::parse) ?: now
        }

        /** Counts and sends every finished hour not reported yet, oldest first, within [BACKLOG]. */
        fun report(): UsageReportRound {
            if (!connection.hasScope(SupportScope.USAGE)) {
                reportNothingBefore(EpistolaClock.instant())
                return UsageReportRound(emptyList(), "the usage service is not granted")
            }
            // An outage, not a choice: the hours wait and go once the service is back.
            if (connection.lastContactFailed()) return UsageReportRound(emptyList(), "the support service was unreachable at the last contact")
            val restriction = jdbi.withHandle<SharingRestriction?, Exception> { SharingRestrictions.of(it, USAGE_SECTION, connection.id) }
            if (restriction == SharingRestriction.NOT_ALLOWED) {
                reportNothingBefore(EpistolaClock.instant())
                return UsageReportRound(emptyList(), "usage is not allowed in this installation")
            }
            return jdbi.open().use { lock ->
                if (!lock.createQuery("SELECT pg_try_advisory_lock(:key)").bind("key", lockKey).mapTo(Boolean::class.java).one()) {
                    return UsageReportRound(emptyList(), "another node is reporting")
                }
                try {
                    round(restriction == SharingRestriction.REQUIRED)
                } finally {
                    lock.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", lockKey).mapTo(Boolean::class.java).one()
                }
            }
        }

        private fun round(required: Boolean): UsageReportRound {
            val now = EpistolaClock.instant()
            val current = now.truncatedTo(ChronoUnit.HOURS)
            val backlog = backlog(properties.usage.backlog, statistics.hourlyRetention)
            forgetHoursBefore(current.minus(backlog).minus(KEPT_BEYOND_BACKLOG))
            val first = maxOf(current.minus(backlog), reportingSince(now))
            val sent = mutableListOf<Instant>()
            for (hour in hoursToReport(first, current)) {
                val readings = MediatorContext.runWithMediator(mediator) { GetGenerationReadings(hour).query() }
                // The statistics lag the generation results by minutes; an hour goes once they hold all of it.
                if (!readings.complete) return UsageReportRound(sent, STATISTICS_BEHIND)
                val body = body(readings, required)
                try {
                    connection.restClient().put()
                        .uri("/usage/hours/{hour}", hour.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity()
                } catch (e: Exception) {
                    if (e.isInsufficientScope()) {
                        // Epistola withdrew usage: every node stops using it until a check finds it granted again.
                        connection.scopeRefused(SupportScope.USAGE)
                        log.info("Usage report stopped: the usage service is not granted")
                        return UsageReportRound(sent, "the usage service is not granted")
                    }
                    if (!e.isSupportUnavailable() && e.refusedForGood()) {
                        // Refused for good (a 4xx): skipped, so the hours after it still go; their running totals carry it.
                        log.warn("Usage report for {} refused by the support service ({}); skipping that hour", hour, e.javaClass.simpleName)
                        markReported(hour)
                        continue
                    }
                    val why = if (e.isSupportUnavailable()) "the support service is unavailable" else "the support service refused $hour: ${e.javaClass.simpleName}"
                    log.info("Usage report stopped: {}", why)
                    return UsageReportRound(sent, why)
                }
                markReported(hour)
                sent += hour
            }
            return UsageReportRound(sent)
        }

        /** Forgets which hours before [before] went out: no round looks back that far. */
        private fun forgetHoursBefore(before: Instant) {
            jdbi.useHandle<Exception> {
                it.createUpdate("DELETE FROM support_usage_reports WHERE connection_id = :connection AND hour < :before")
                    .bind("connection", connection.id)
                    .bind("before", before.atOffset(ZoneOffset.UTC))
                    .execute()
            }
        }

        /** Records [hour] as done, sent or skipped: it is not tried again. */
        private fun markReported(hour: Instant) {
            jdbi.useHandle<Exception> {
                it.createUpdate(
                    "INSERT INTO support_usage_reports (connection_id, hour, reported_at) VALUES (:connection, :hour, :now) " +
                        "ON CONFLICT (connection_id, hour) DO UPDATE SET reported_at = EXCLUDED.reported_at",
                )
                    .bind("connection", connection.id)
                    .bind("now", EpistolaClock.offsetDateTime())
                    .bind("hour", hour.atOffset(ZoneOffset.UTC))
                    .execute()
            }
        }

        /** A 4xx other than an authentication problem: sending the same report again gets the same answer. */
        private fun Exception.refusedForGood(): Boolean = (this as? org.springframework.web.client.RestClientResponseException)
            ?.statusCode?.let { it.is4xxClientError && it.value() != 401 && it.value() != 429 } == true

        /** Finished hours from [first] not reported yet, oldest first. */
        private fun hoursToReport(first: Instant, current: Instant): List<Instant> {
            val all = generateSequence(first.truncatedTo(ChronoUnit.HOURS)) { it.plus(1, ChronoUnit.HOURS) }.takeWhile { it.isBefore(current) }.toList()
            if (all.isEmpty()) return emptyList()
            val reported = jdbi.withHandle<Set<Instant>, Exception> { handle ->
                handle.createQuery("SELECT hour FROM support_usage_reports WHERE connection_id = :connection AND hour >= :first AND hour < :current")
                    .bind("connection", connection.id)
                    .bind("first", all.first().atOffset(ZoneOffset.UTC))
                    .bind("current", current.atOffset(ZoneOffset.UTC))
                    .map { rs, _ -> rs.getObject("hour", OffsetDateTime::class.java).toInstant() }
                    .set()
            }
            return all.filterNot { it in reported }
        }

        /**
         * The hour's report: for each sharing tenant that has used anything, its figures for the hour and
         * its running totals up to the end of the hour, under the key it is sent as. A sharing tenant is in
         * every report, also with nothing in the hour: one missing from a report reads as having stopped.
         */
        private fun body(readings: GenerationReadings, required: Boolean): String {
            val sharing = if (required) null else sharing(readings.readings.map { it.tenantKey.value }.toSet())
            val tenants = objectMapper.createArrayNode()
            readings.readings.filter { sharing == null || it.tenantKey.value in sharing }.forEach {
                tenants.addObject()
                    .put("tenant", references.onWire(it.tenantKey.value, connection.id))
                    .put("generated", it.completed)
                    .put("previews", it.previews)
                    .put("generatedTotal", it.completedTotal)
                    .put("previewsTotal", it.previewsTotal)
            }
            return objectMapper.writeValueAsString(objectMapper.createObjectNode().set("tenants", tenants))
        }

        /** Which of [tenants] share usage now: their own choice, or the installation default. A deleted tenant follows the default. */
        private fun sharing(tenants: Set<String>): Set<String> = jdbi.withHandle<Set<String>, Exception> { handle ->
            val default = handle.createQuery("SELECT share FROM support_sharing_default WHERE connection_id = :connection AND section = '$USAGE_SECTION'").bind("connection", connection.id)
                .mapTo(Boolean::class.java).findOne().orElse(false)
            val own = handle.createQuery("SELECT tenant_key, withdrawn_at IS NULL AS share FROM support_sharing_choice WHERE connection_id = :connection AND section = '$USAGE_SECTION'").bind("connection", connection.id).map { rs, _ -> rs.getString("tenant_key") to rs.getBoolean("share") }.list().toMap()
            tenants.filter { own[it] ?: default }.toSet()
        }
    }

    /** Whether this hour's moment has passed; every connection usage may go to reports what waits. Run by [UsageReportScheduler]. */
    fun tick() {
        val now = EpistolaClock.instant()
        if (now.isBefore(now.truncatedTo(ChronoUnit.HOURS).plus(reportMoment()))) return
        val current = connections()
        // A connection removed or no longer taking usage leaves no reporter behind.
        reporters.keys.retainAll(current.map { it.id }.toSet())
        current.forEach { reporterFor(it).tick(now) }
    }

    /** Counts and sends every finished hour not reported yet to Epistola support, oldest first. */
    fun report(): UsageReportRound = reporterFor(discovered).report()

    /** [report] for connection [connectionId]; nothing when usage may not go there. */
    fun report(connectionId: UUID): UsageReportRound = connections().firstOrNull { it.id == connectionId }?.let { reporterFor(it).report() }
        ?: UsageReportRound(emptyList(), "no such connection takes usage")

    companion object {
        const val MOMENT_KEY = "support.usage.report-moment"

        /** The reporting moment falls within this many minutes after the hour. */
        const val MOMENT_WINDOW_MINUTES = 10L
        const val SINCE_KEY = "support.usage.reporting-since"
        const val STATISTICS_BEHIND = "the generation statistics do not hold the whole hour yet"

        /**
         * Which hours went out is kept this long past the backlog, then forgotten: no round looks
         * back further, and a node whose clock runs behind still finds what it needs.
         */
        val KEPT_BEYOND_BACKLOG: Duration = Duration.ofDays(1)

        /**
         * How far back hours are reported: the [configured] backlog (`epistola.support.usage.backlog`),
         * but never an hour the generation statistics no longer keep by the hour
         * (`epistola.statistics.hourly-retention`), which would read as empty.
         */
        fun backlog(configured: Duration, hourlyRetention: Duration): Duration = minOf(configured, hourlyRetention.minusHours(1))

        /** How long the next round waits after one that could not send everything. */
        val RETRY_AFTER: Duration = Duration.ofMinutes(5)

        /** The advisory lock that lets one node report at a time. */
        private const val LOCK_KEY = 0x55534147_45L
    }
}
