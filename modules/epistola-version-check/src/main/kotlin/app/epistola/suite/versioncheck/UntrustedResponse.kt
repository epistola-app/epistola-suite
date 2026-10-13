// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import java.net.URI

/**
 * Responses from epistola.app are untrusted input: they are rendered in the authenticated UI, so
 * text is cleaned and capped, and a link survives only when it is an absolute `https:` URL.
 * Thymeleaf escapes text on output; this is the second line of defence, not the only one.
 */
internal object UntrustedResponse {
    const val MAX_TEXT = 300
    const val MAX_URL = 2048

    /** Trimmed, control characters removed, capped at [max] characters; null when empty. */
    fun text(value: String?, max: Int = MAX_TEXT): String? {
        val cleaned = value?.filterNot { it.isISOControl() }?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > max) cleaned.take(max - 1) + "…" else cleaned
    }

    /** The value when it is an absolute `https:` URL with a host, else null. */
    fun httpsUrl(value: String?): String? {
        if (value.isNullOrBlank() || value.length > MAX_URL) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        return if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) value.trim() else null
    }
}
