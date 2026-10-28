// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.domain

import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.kotlin.mapTo

/**
 * Which emits into `generation_results` have not committed yet.
 *
 * A result's `sequence` is handed out when it is inserted, but results commit in whatever order
 * their transactions finish: sequence n can still be uncommitted while n + 1 is visible. A consumer
 * given n + 1 acknowledges it, and the acknowledgement moves its cursors past n for good. So a page
 * may only reach up to a sequence below which nothing is still in flight.
 *
 * Every emit takes a shared advisory lock, held until its transaction ends, before it draws its
 * sequence. A poll reads the highest sequence handed out so far, then the emits holding the lock
 * at that moment: those are the only ones that can hold a sequence at or below it. Once they are
 * gone, everything up to that sequence is committed or rolled back, and can be delivered.
 *
 * The lock is keyed per tenant, `EPIS` and `hashtext(tenant_key)`, because a poll serves one tenant
 * and only that tenant's rows can be held back: one tenant's slow emit does not delay another's
 * feed. Two tenants whose keys hash alike share a lock, so a poll may wait for the other's emits
 * too, which is slower but still correct.
 *
 * Emits never wait on a poll: the poll reads `pg_locks` instead of taking the lock itself, and
 * nothing ever takes the lock exclusively, so it cannot deadlock. See `docs/collect.md`.
 */
object EmitsInFlight {
    // The two-key form of the advisory lock, so it shows in pg_locks as classid/objid/objsubid = 2;
    // the second key is the tenant's hashtext, which pg_locks shows as an oid.
    private const val LOCK_CLASS = 0x45504953 // "EPIS"

    /**
     * Mark the current transaction as an emit in flight for [tenant]. Call before the INSERT draws a
     * sequence.
     */
    fun enter(handle: Handle, tenant: TenantKey) {
        handle.createQuery("SELECT pg_advisory_xact_lock_shared(:class, hashtext(:tenant))::text")
            .bind("class", LOCK_CLASS)
            .bind("tenant", tenant)
            .mapTo<String>()
            .one()
    }

    /** The highest sequence handed out so far, committed or not; 0 before the first. */
    fun highestIssued(handle: Handle): Long = handle.createQuery(
        "SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM generation_results_sequence_seq",
    ).mapTo<Long>().one()

    /** The other transactions that are [tenant]'s emits in flight right now, by virtual transaction id. */
    fun holders(handle: Handle, tenant: TenantKey): List<String> = handle.createQuery(
        """
        SELECT virtualtransaction
        FROM pg_locks
        WHERE locktype = 'advisory'
          AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
          AND classid = :class::oid AND objid = hashtext(:tenant)::oid AND objsubid = 2
          AND granted
          AND pid <> pg_backend_pid()
        """,
    )
        .bind("class", LOCK_CLASS)
        .bind("tenant", tenant)
        .mapTo<String>()
        .list()

    /** Whether any of [holders] is still an emit in flight for [tenant]. */
    fun anyStillInFlight(handle: Handle, tenant: TenantKey, holders: List<String>): Boolean = handle.createQuery(
        """
        SELECT EXISTS (
            SELECT 1
            FROM pg_locks
            WHERE locktype = 'advisory'
              AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
              AND classid = :class::oid AND objid = hashtext(:tenant)::oid AND objsubid = 2
              AND virtualtransaction = ANY(:holders)
        )
        """,
    )
        .bind("class", LOCK_CLASS)
        .bind("tenant", tenant)
        .bindArray("holders", String::class.java, *holders.toTypedArray())
        .mapTo<Boolean>()
        .one()
}
