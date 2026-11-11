// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

/**
 * When the next poll for support request changes is due. The scheduler ticks every [intervalMs];
 * a poll runs on a tick once [currentWaitMs] has passed since the last round. That wait is
 * [intervalMs] while changes come in, and after a round that found nothing twice the last one, up to
 * [maxIntervalMs]. Times are [app.epistola.suite.support.time.SupportTime] readings, never wall-clock. Kept by the node that owns
 * the poll; a node that takes it over starts at [intervalMs].
 */
class SupportRequestPollPacing(private val intervalMs: Long, private val maxIntervalMs: Long) {
    @Volatile private var nextDueNanos: Long? = null

    @Volatile private var waitMs = intervalMs

    /** A poll may start on the tick at [nowNanos]. */
    fun isDue(nowNanos: Long): Boolean = nextDueNanos?.let { nowNanos - it >= 0 } ?: true

    /** The round that started at [nowNanos] [foundChanges] or not: sets when the next is due. */
    fun record(foundChanges: Boolean, nowNanos: Long) {
        waitMs = if (foundChanges) intervalMs else minOf(waitMs * 2, maxIntervalMs)
        // Half a tick early, so the tick that comes [waitMs] after this round is never just missed.
        nextDueNanos = nowNanos + (waitMs - intervalMs / 2) * NANOS_PER_MS
    }

    /**
     * Runs one round that started at [startNanos] and paces the next from that start, so a long round
     * does not skip the next tick. A round that throws counts as one that found nothing, so a failing
     * service is polled less often, not every tick.
     */
    fun round(startNanos: Long, poll: () -> Boolean): Boolean {
        var found = false
        try {
            found = poll()
            return found
        } finally {
            record(found, startNanos)
        }
    }

    /** How long after the last round the next one runs. */
    fun currentWaitMs(): Long = waitMs

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
