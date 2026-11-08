// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.support.connection.isSupportUnavailable
import org.springframework.web.client.RestClientResponseException
import java.time.Duration

/**
 * How often an item the support service would not take is tried again (`docs/support-protocol.md`,
 * "Reliability"). Nothing is given up for good: after a refusal the wait doubles per attempt from
 * [FIRST] up to [MAX]; a server error on one item alone waits [FIRST] without counting.
 */
object SupportRequestSyncBackoff {
    val FIRST: Duration = Duration.ofMinutes(1)
    val MAX: Duration = Duration.ofHours(6)

    /** The wait after the [attempts]-th refusal in a row. */
    fun afterRefusals(attempts: Int): Duration {
        val delay = FIRST.multipliedBy(1L shl (attempts - 1).coerceIn(0, 20))
        return if (delay > MAX) MAX else delay
    }

    /** Server errors on single items in a row after which a round stops: the service is more likely failing as a whole. */
    const val MAX_ITEM_SERVER_ERRORS_IN_A_ROW = 3
}

/** How one item's failure bears on the item and on the round. */
enum class SupportRequestSyncFailure {
    /** "Not now" for everything: unreachable, overloaded, not granted. Nothing counted; the round stops. */
    STOP_ROUND,

    /** The service failed on this item alone (a `500`): it waits a little, uncounted, and the round goes on. */
    ITEM_SERVER_ERROR,

    /** The service refused this item: one attempt counted, it backs off, the round goes on. */
    REFUSED,
}

/**
 * Classifies a send failure. A `500` is the service failing on this item (a bug for one payload,
 * say): the items behind it still go. Everything else that means "not now" — I/O, a deadline, a
 * gateway's `502`/`504`, `503`, `429`, a missing scope — stops the round.
 */
fun Throwable.supportRequestSyncFailure(): SupportRequestSyncFailure = when {
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(16).any { it is RestClientResponseException && it.statusCode.value() == 500 } ->
        SupportRequestSyncFailure.ITEM_SERVER_ERROR

    isSupportRequestSyncDeferred() || isSupportUnavailable() -> SupportRequestSyncFailure.STOP_ROUND

    else -> SupportRequestSyncFailure.REFUSED
}
