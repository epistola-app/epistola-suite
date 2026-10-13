// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import java.time.Instant

/**
 * A request to Epistola exactly as the Suite sends it. The clients build it and send it, and the
 * "Shared with Epistola" page shows the same object, so the page cannot drift from what is sent.
 */
data class OutgoingRequest(
    val url: String,
    /** The headers the Suite sets (not the HTTP client's own, such as Host). */
    val headers: Map<String, String>,
) {
    /** The request line, then one header per line: how an operator reads an HTTP request. */
    fun toText(): String = (listOf("GET $url") + headers.map { (name, value) -> "$name: $value" }).joinToString("\n")
}

/** One daily check, as an operator sees it: whether it runs, what it sends, and how it last went. */
data class CheckSummary(
    val enabled: Boolean,
    /** What the check sends with the current settings; null when it is off. */
    val request: OutgoingRequest?,
    val lastRunAt: Instant?,
    /** `200`, `503`, `OK`, or the error; null when it never ran. */
    val lastResult: String?,
    /** What the last run actually sent, when the check records it. */
    val lastRequest: OutgoingRequest?,
)
