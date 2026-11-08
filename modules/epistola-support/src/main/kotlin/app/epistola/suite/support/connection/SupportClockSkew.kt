// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.slf4j.Logger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Tells a refusal of the registration from a refusal of this node's clock (`docs/support-protocol.md`,
 * "A node whose clock is off"). The support service answers `invalid_client` both to a revoked
 * registration and to an assertion whose `iat`/`exp` are outside its skew
 * ([SupportClientKeys.CLOCK_SKEW_TOLERANCE]).
 */
internal object SupportClockSkew {
    /**
     * Confirms [refused], an `invalid_client` for an assertion dated [now] by this node's clock. When
     * the answer's `Date` is further from [now] than the service allows, asks once more through
     * [retry] with an assertion dated by the service's clock. Refused again, that refusal is thrown,
     * so a revoked registration still becomes Refused; accepted, it was the clock: throws a
     * [SupportNodeClockException] naming node [nodeId] and the difference, and nothing is marked
     * refused or forgotten. The token [retry] got is not used: a node whose clock is off must have
     * it set right, not work around it. Within the tolerance, or without a `Date`, throws [refused].
     */
    fun confirm(refused: SupportRefusedException, now: Instant, nodeId: String, log: Logger, retry: (Instant) -> Unit): Nothing {
        val serviceTime = refused.serviceTime ?: throw refused
        val skew = Duration.between(serviceTime, now)
        if (skew.abs() <= SupportClientKeys.CLOCK_SKEW_TOLERANCE) throw refused
        val clock = "this node's clock (node $nodeId, ${now.truncatedTo(ChronoUnit.SECONDS)}) is ${about(skew)} " +
            "${if (skew.isNegative) "behind" else "ahead of"} the support service's (${serviceTime.truncatedTo(ChronoUnit.SECONDS)}), " +
            "which allows ${SupportClientKeys.CLOCK_SKEW_TOLERANCE.seconds} seconds"
        try {
            retry(serviceTime)
        } catch (again: SupportRefusedException) {
            log.info("Support connection: {}, but the support service also refused an assertion dated by its own clock: a refusal", clock)
            throw again
        } catch (e: SupportUnavailableException) {
            throw SupportUnavailableException("The support service refused this node's client assertion (${refused.message}), and $clock; confirming the refusal failed: ${e.message}", e)
        }
        throw SupportNodeClockException(
            "The support service refuses this node's client assertions because $clock. Set this node's clock right (NTP); the registration is not refused",
            refused,
        )
    }

    /** [duration] for people: seconds under two minutes, then minutes, hours, days, rounded. */
    fun about(duration: Duration): String {
        val s = duration.abs().seconds
        return when {
            s < 120 -> "$s seconds"
            s < 2 * 3600 -> "about ${(s + 30) / 60} minutes"
            s < 2 * 86400 -> "about ${(s + 1800) / 3600} hours"
            else -> "about ${(s + 43200) / 86400} days"
        }
    }
}
