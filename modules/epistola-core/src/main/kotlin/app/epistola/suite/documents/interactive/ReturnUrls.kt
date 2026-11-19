// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where the form sends the browser after the person submits or cancels (contract ADR 0011, "Coming
 * back"): the caller's `returnUrl` with `epistolaRequestId` and `epistolaRequestStatus` added after
 * the query it already has, replacing a parameter of the same name, its fragment kept, and nothing
 * else added (no user input ever goes in a URL).
 */
object ReturnUrls {
    const val REQUEST_ID = "epistolaRequestId"
    const val REQUEST_STATUS = "epistolaRequestStatus"

    /** [status] is the contract's wire value: `pending` once submitted, `cancelled` once cancelled. */
    fun withOutcome(returnUrl: String, requestId: GenerationRequestKey, status: String): String {
        val fragmentAt = returnUrl.indexOf('#')
        val beforeFragment = if (fragmentAt >= 0) returnUrl.substring(0, fragmentAt) else returnUrl
        val fragment = if (fragmentAt >= 0) returnUrl.substring(fragmentAt) else ""
        val queryAt = beforeFragment.indexOf('?')
        val base = if (queryAt >= 0) beforeFragment.substring(0, queryAt) else beforeFragment
        val kept = if (queryAt >= 0) {
            beforeFragment.substring(queryAt + 1).split('&').filter { it.isNotEmpty() && parameterName(it) !in OURS }
        } else {
            emptyList()
        }
        val added = listOf("$REQUEST_ID=${encode(requestId.value.toString())}", "$REQUEST_STATUS=${encode(status)}")
        return "$base?${(kept + added).joinToString("&")}$fragment"
    }

    private val OURS = setOf(REQUEST_ID, REQUEST_STATUS)

    private fun parameterName(pair: String): String = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
