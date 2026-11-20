// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import java.net.URI

/**
 * An allowed start of a URL a form may send the browser to or link to (contract ADR 0011, "Coming
 * back"): an origin, and optionally a path under it, such as `https://cases.example.org/zaken`.
 *
 * Not a string prefix, which would let `https://cases.example.org` match
 * `https://cases.example.org.evil.example`, and `/zaken` match `/zaken-other` or `/zaken/../admin`.
 * A URL matches when its scheme, host and port are exactly this origin, and its path, with dot
 * segments resolved, is this path or lies under it at a `/`. A URL whose path hides a dot segment or
 * a separator behind percent-encoding (`%2e`, `%2f`, `%5c`) or a backslash never matches: a browser
 * would resolve them where this check could not.
 *
 * @property origin `scheme://host[:port]`, lowercased, without a default port.
 * @property path empty for the whole origin, else starting with `/` and never ending with one.
 */
data class ReturnUrlPrefix(val origin: String, val path: String) {

    /** Whether [url] starts with this prefix, as the class describes. */
    fun matches(url: String): Boolean {
        if (Origins.of(url) != origin) return false
        val candidate = pathOf(url) ?: return false
        return path.isEmpty() || candidate == path || candidate.startsWith("$path/")
    }

    /** How it is stored and shown: `https://cases.example.org/zaken`. */
    override fun toString(): String = origin + path

    companion object {
        /**
         * [entry] as a prefix, or null when it is not an absolute http(s) URL, or carries credentials,
         * a query, a fragment, or a path that [matches] would never accept.
         */
        fun parse(entry: String): ReturnUrlPrefix? {
            val trimmed = entry.trim()
            val origin = Origins.of(trimmed) ?: return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val path = pathOf(trimmed) ?: return null
            return ReturnUrlPrefix(origin, path.trimEnd('/'))
        }

        /**
         * The path of [url] with dot segments resolved, or null when it cannot be checked safely: not
         * a URI, or hiding a dot segment or separator from [URI.normalize].
         */
        private fun pathOf(url: String): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val raw = uri.rawPath.orEmpty()
            if (raw.contains('\\') || HIDDEN.containsMatchIn(raw)) return null
            val normalised = uri.normalize().rawPath.orEmpty()
            // A path that climbs above the root keeps its `..` after normalising.
            if (normalised.split('/').any { it == ".." }) return null
            return normalised
        }

        private val HIDDEN = Regex("%(2e|2f|5c)", RegexOption.IGNORE_CASE)
    }
}
