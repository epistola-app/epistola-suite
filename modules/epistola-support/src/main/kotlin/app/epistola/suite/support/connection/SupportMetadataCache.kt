// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.time.EpistolaClock
import org.springframework.web.client.RestClientException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The support service's verified metadata (RFC 8414) as this node last read it, kept for an hour per
 * issuer. One request at a time: a caller never queues behind another's, which may hang until its
 * deadline, but gives up after a quarter second.
 */
internal class SupportMetadataCache(private val client: SupportServiceClient, private val allowHttp: Boolean) {
    @Volatile private var cached: SupportCachedMetadata? = null
    private val lock = ReentrantLock()

    /** What this node last read, whatever its age; null before the first read. No network I/O. */
    fun cached(): SupportServiceMetadata? = cached?.metadata

    /** [issuer]'s metadata: the cached copy while it is fresh, else read again. */
    fun forIssuer(issuer: URI): SupportServiceMetadata {
        cached?.takeIf { it.metadata.issuer.toString().trimEnd('/') == issuer.toString().trimEnd('/') && it.fetchedAt.plus(TTL).isAfter(EpistolaClock.instant()) }
            ?.let { return it.metadata }
        return refresh(issuer)
    }

    /** Reads [issuer]'s metadata now. */
    fun refresh(issuer: URI): SupportServiceMetadata {
        if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw SupportUnavailableException("Another request for the support service metadata is still running")
        }
        return try {
            client.metadata(issuer, allowHttp).also { cached = SupportCachedMetadata(it, EpistolaClock.instant()) }
        } catch (e: RestClientException) {
            throw SupportUnavailableException("Failed reading the support service metadata: ${describeNoAnswer(e)}", e)
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        val TTL: Duration = Duration.ofHours(1)
        const val LOCK_WAIT_MS = 250L
    }
}
