// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import java.net.URI

/**
 * Web origins (`scheme://host[:port]`) as allowlists compare them: lowercase, the default port
 * dropped, no path. Matching is exact, never a prefix or a wildcard, so
 * `https://cases.example.org.evil.test` is not `https://cases.example.org`.
 */
object Origins {
    /** The origin of [url], or null when it is not an absolute http(s) URL with a host. */
    fun of(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.ifEmpty { null } ?: return null
        val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        return if (port == null) "$scheme://$host" else "$scheme://$host:$port"
    }

    /** [origin] normalised as [of] does, or null when it is not an origin alone (it has a path, query or fragment). */
    fun normalise(origin: String): String? {
        val trimmed = origin.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
        return of(trimmed)
    }
}
