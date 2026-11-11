// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import org.slf4j.Logger

/**
 * How a refusal by the support service is logged. A refused registration is checked again on every
 * interval, so a registration Epistola restores is noticed; only the change to refused is a
 * warning, and every check after it that is still refused logs at debug. Decided from the stored
 * status, so it holds across nodes, whichever one runs the check.
 */
internal object SupportRefusalLog {
    fun log(log: Logger, previous: StoredSupportStatus?, clientId: String?, reason: String) {
        val stillRefused = previous?.state == StoredConnectionState.REFUSED && previous.clientId == clientId
        if (stillRefused) {
            log.debug("Support connection: a registration is still refused registration={} reason=\"{}\"", clientId, reason)
        } else {
            log.warn("Support connection: the support service refused a registration registration={} reason=\"{}\"", clientId, reason)
        }
    }
}
