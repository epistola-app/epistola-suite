package app.epistola.suite.logs

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for application-log capture, storage and retention.
 *
 * Capture is non-blocking, batched and fail-open: log events are offered to a
 * bounded in-memory queue and flushed to `application_log` by a single
 * background worker. Overflow is dropped (and counted) rather than blocking the
 * thread that logged.
 */
@ConfigurationProperties(prefix = "epistola.logs")
data class ApplicationLogProperties(
    /** Master switch — when false, no appender is attached and nothing is captured. */
    val enabled: Boolean = true,
    /** Minimum level captured to the table (ERROR/WARN/INFO/DEBUG/TRACE). Default INFO to bound volume. */
    val level: String = "INFO",
    /** Rows older than this many days are pruned by the nightly retention task. */
    val retentionDays: Long = 7,
    /** Bounded queue capacity; once full, further events are dropped (and counted). */
    val queueCapacity: Int = 10_000,
    /**
     * Maximum log events captured to the DB per second — a token bucket applied at
     * enqueue. Excess is dropped and counted as `epistola.logs.rate.limited`. This
     * guards against a "log bomb" (a runaway/looping logger) bloating the table and
     * loading the database faster than retention can reclaim. The bucket starts full,
     * so normal bursts up to this size pass untouched — only a *sustained* flood is
     * shed. `0` disables the cap.
     */
    val maxRatePerSecond: Int = 2_000,
    /** Maximum number of records flushed per batched insert. */
    val batchSize: Int = 200,
    /** Maximum time the drain worker waits for the first record before looping. */
    val flushIntervalMs: Long = 1_000,
    /** Cron (in UTC) for the retention delete. Default 03:30 daily. */
    val retentionCron: String = "0 30 3 * * ?",
)
