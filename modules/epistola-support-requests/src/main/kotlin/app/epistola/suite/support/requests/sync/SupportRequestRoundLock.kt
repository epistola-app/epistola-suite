// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.support.connection.SupportHttp
import app.epistola.suite.support.requests.adapters.RestSupportRequestSyncAdapter
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps feedback sending rounds to one node of the cluster at a time. Without it a change on one
 * node starts a round there while another node runs its own, and both send every item still
 * waiting: twice the traffic, and a status changed twice in quick succession on two nodes could
 * reach the support service in the wrong order.
 *
 * A lease in a row of its own (`app_metadata`, [KEY]: the holder and `claimedAt`), taken and given
 * back in short statements and stamped by the database's clock: no connection is held while the round talks to the support
 * service, so it works through a pooler in transaction mode and never trips the pool's leak
 * detection, and a node whose own clock is off neither takes over a live round nor holds a dead one
 * longer. A round on a node that dies keeps the lease until it runs out ([lease]: the round budget
 * plus the deadline of the largest call, plus a minute); the next sweep after that sends what is left.
 */
@Component
class SupportRequestRoundLock(
    private val jdbi: Jdbi,
    properties: SupportRequestSyncProperties,
    supportHttp: SupportHttp,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * How long a round may hold the lock: it starts no item after its budget, and the item under way
     * then ends within one call's deadline (the largest body is a screenshot).
     */
    val lease: Duration = Duration.ofMillis(properties.roundBudgetMs)
        .plus(supportHttp.deadline(RestSupportRequestSyncAdapter.MAX_ASSET_BYTES))
        .plus(LEASE_MARGIN)

    /**
     * Runs [round] while holding the lock and returns true; waits for a round on another node to
     * finish first, looking again every [POLL], for at most [maxWait]. Returns false, without running
     * [round], when the lock stayed taken (the next sweep then sends what is left) or the thread was
     * interrupted (shutdown).
     */
    fun runExclusively(maxWait: Duration, round: () -> Unit): Boolean {
        val until = System.nanoTime() + maxWait.toNanos()
        while (true) {
            if (tryRun(round)) return true
            if (System.nanoTime() >= until) return false
            try {
                Thread.sleep(POLL.toMillis())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun tryRun(round: () -> Unit): Boolean {
        val holder = UUIDv7.generate().toString()
        if (!claim(holder)) return false
        try {
            round()
        } finally {
            if (!release(holder)) log.warn("Feedback sync: this round outlived its lease and another round may have run alongside it lease={}", lease)
        }
        return true
    }

    /** Takes the lease for [holder] when it is free or ran out, by the database's clock. */
    private fun claim(holder: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
            INSERT INTO app_metadata (key, value, updated_at)
            VALUES (:key, jsonb_build_object('holder', :holder, 'claimedAt', clock_timestamp()), NOW())
            ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
            WHERE app_metadata.value ->> 'holder' IS NULL
               OR (app_metadata.value ->> 'claimedAt')::timestamptz < clock_timestamp() - make_interval(secs => :leaseSeconds)
            RETURNING TRUE
            """,
        )
            .bind("key", KEY)
            .bind("holder", holder)
            .bind("leaseSeconds", lease.seconds.toDouble())
            .mapTo(Boolean::class.java)
            .findOne()
            .orElse(false)
    }

    /** Gives the lease back, unless another round took it over meanwhile; true when it was still [holder]'s. */
    private fun release(holder: String): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate("UPDATE app_metadata SET value = '{}'::jsonb, updated_at = NOW() WHERE key = :key AND value ->> 'holder' = :holder")
            .bind("key", KEY)
            .bind("holder", holder)
            .execute() > 0
    }

    companion object {
        /** The `app_metadata` row of the lease. */
        const val KEY = "feedback.sync.round"

        /** How often a node waiting for another node's round looks again. */
        val POLL: Duration = Duration.ofMillis(250)

        /** What the lease allows on top of the round budget and one call's deadline: the round's database work. */
        val LEASE_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
