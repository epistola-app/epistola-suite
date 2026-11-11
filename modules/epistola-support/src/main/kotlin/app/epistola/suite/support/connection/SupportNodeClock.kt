// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.time.EpistolaClock
import org.slf4j.Logger
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

// What DefaultSupportConnection reads from the store and keeps about this node's clock.

/**
 * The stored rows as last read, with when, and the [SupportConnectionStore.version] read before
 * them (null when it could not be read); one entry, so bounded.
 */
internal class SupportRowsSnapshot(
    val credentials: StoredSupportCredentials?,
    val status: StoredSupportStatus?,
    val issuer: URI?,
    val services: StoredSupportServices?,
    /** The scopes refused on any node, with the version each refusal raised ([SupportConnectionStore.recordRefusal]). */
    val refusals: Map<SupportScope, Long>,
    /** The nodes whose clock is off, with the message each stored ([SupportConnectionStore.nodeClockErrors]). */
    val nodeClocks: Map<String, String>,
    val version: Long?,
    val readAtNanos: Long,
    /** A row could not be read and a stand-in is used: read again after a second, whatever the version says. */
    val partial: Boolean,
    /** When [version] was last found unchanged. */
    @Volatile var versionCheckedAt: Instant,
)

/**
 * Whether this node's clock is off: the service refused its client assertion for the clock alone
 * ([SupportNodeClockException]), and no token was fetched since. While it is, this node does not
 * advertise [SupportCluster.CAPABILITY], so the cluster-wide support work runs on other nodes, and
 * its clock error is stored under [nodeId] for every node's page ([SupportConnectionStore.nodeClockErrors]).
 */
internal class SupportNodeClock(private val store: SupportConnectionStore, private val nodeId: String, private val log: Logger) {
    @Volatile private var failure: SupportNodeFailure? = null

    /** When this node last stored its clock error ([System.nanoTime]); stored again every five minutes at most. */
    @Volatile private var storedAtNanos: Long? = null

    fun isOff(): Boolean = failure != null

    /**
     * The service refused this node's client assertion for its clock alone: says so once at WARN, and
     * stores [message] for every node's page, again at most every five minutes while it lasts, so the
     * entry stays fresh. Never throws. True when the stored rows changed.
     */
    fun noteOff(message: String): Boolean {
        val first = failure == null
        failure = SupportNodeFailure(EpistolaClock.instant(), message)
        if (first) {
            log.warn("Support connection: this node's clock is off. Until it is right, this node sends nothing to the support service and leaves the scheduled support work to the other nodes reason=\"{}\"", message)
        }
        val storedAt = storedAtNanos
        if (!first && storedAt != null && System.nanoTime() - storedAt < RESTORE_NANOS) return false
        return try {
            store.recordNodeClockError(nodeId, message)
            storedAtNanos = System.nanoTime()
            true
        } catch (e: RuntimeException) {
            log.warn("Support connection: could not store that this node's clock is off reason=\"{}\"", e.message)
            false
        }
    }

    /**
     * A token was fetched: this node's clock is fine (again). Clears its stored clock error when
     * [storedForThisNode] or it was off. Never throws. True when the stored rows changed.
     */
    fun setRight(storedForThisNode: Boolean): Boolean {
        val was = failure
        if (was == null && !storedForThisNode) return false
        reset()
        val cleared = try {
            store.clearNodeClockError(nodeId)
        } catch (e: RuntimeException) {
            log.warn("Support connection: could not clear this node's stored clock error reason=\"{}\"", e.message)
            false
        }
        if (was != null) log.info("Support connection: the support service accepts this node's client assertions again; this node takes on support work again")
        return cleared
    }

    /** Forgets this node's clock state, not the stored entry; for tests. */
    fun reset() {
        failure = null
        storedAtNanos = null
    }

    private companion object {
        /** A node whose clock stays off stores its clock error again after this long. */
        val RESTORE_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
