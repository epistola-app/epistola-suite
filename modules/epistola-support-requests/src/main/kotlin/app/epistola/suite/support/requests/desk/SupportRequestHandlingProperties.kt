// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `epistola.support.requests.handled-by` (Helm `support.requests.handledBy`): who handles new support
 * requests until an installation administrator saves a choice on Platform → Support connections, which
 * then wins. `inbox` (the default): the installation's own support desk, which passes requests on. Or a
 * support connection, named by its endpoint (ADR 0053 §4), such as `https://support.epistola.app`; while
 * no connection has that endpoint, the inbox handles them.
 */
@ConfigurationProperties(prefix = "epistola.support.requests")
data class SupportRequestHandlingProperties(
    val handledBy: String = INBOX,
) {
    init {
        val value = handledBy.trim()
        require(value.equals(INBOX, ignoreCase = true) || runCatching { java.net.URI(value) }.getOrNull()?.let { it.isAbsolute && it.host != null && it.scheme in setOf("https", "http") } == true) {
            "epistola.support.requests.handled-by must be inbox or a support connection's endpoint; got '$handledBy'"
        }
    }

    /** The endpoint named, without a trailing slash; null for the inbox. */
    val endpoint: String? get() = handledBy.trim().takeUnless { it.equals(INBOX, ignoreCase = true) }?.trimEnd('/')

    companion object {
        const val INBOX = "inbox"
    }
}
