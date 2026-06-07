package app.epistola.suite.scheduling

import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Cross-instance mutual exclusion for scheduled work, backed by a PostgreSQL session-level
 * advisory lock. In a multi-pod deployment several instances run the same `@Scheduled` method;
 * wrapping the body in [runExclusively] ensures only one instance executes a given cycle at a
 * time. Singleton, installation-wide tasks (e.g. polling one shared cursor) need this; per-row
 * work should prefer `SELECT ... FOR UPDATE SKIP LOCKED` instead (see `JobPoller`).
 *
 * The lock is held on a single dedicated connection for the duration of [block] and released
 * in a `finally`; the block's own mediator/JDBI work uses other pooled connections. If the
 * lock can't be acquired (another instance holds it), [block] is skipped and the method
 * returns without error.
 */
@Component
class SchedulerLock(
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Runs [block] iff this instance can acquire the advisory lock [key]; otherwise no-ops. */
    fun runExclusively(key: Long, block: () -> Unit) {
        jdbi.useHandle<Exception> { handle ->
            val acquired = handle.createQuery("SELECT pg_try_advisory_lock(:key)")
                .bind("key", key)
                .mapTo(Boolean::class.java)
                .one()
            if (!acquired) {
                log.debug("Advisory lock {} held by another instance; skipping cycle", key)
                return@useHandle
            }
            try {
                block()
            } finally {
                handle.createQuery("SELECT pg_advisory_unlock(:key)")
                    .bind("key", key)
                    .mapTo(Boolean::class.java)
                    .one()
            }
        }
    }

    companion object {
        /** Stable advisory-lock key for the feedback inbound poll. */
        const val FEEDBACK_POLL = 815_001L

        /** Stable advisory-lock key for the feedback outbound retry sweep. */
        const val FEEDBACK_RETRY = 815_002L

        /** Stable advisory-lock key for the daily catalog-backup run. */
        const val CATALOG_BACKUP = 815_003L
    }
}
