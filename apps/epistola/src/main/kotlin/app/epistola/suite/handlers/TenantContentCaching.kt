// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.security.MessageDigest

/**
 * How a tenant's own files (its images and fonts) are cached: by the browser that asked for them
 * only (`private`), and checked again on every use (`no-cache`), answered with 304 while the bytes
 * are the ones it holds ([etag]).
 *
 * Not `public`: a shared cache (a proxy, a CDN) would keep a file read with a session and serve it
 * to anyone. Not `immutable` either: the address names the image or font, not its bytes, so a
 * replaced image would otherwise stay stale for as long as the cache kept it. Revalidating is a
 * small request that still passes the read's permission check.
 */
internal object TenantContentCaching {
    const val CACHE_CONTROL: String = "private, no-cache"

    /** The strong entity tag of content stored under [contentHash]. */
    fun etag(contentHash: String): String = "\"$contentHash\""

    /** The entity tag of [bytes], for content read without its stored hash. */
    fun etagOf(bytes: ByteArray): String = etag(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    /** 304 when the request already holds [etag], else null: the caller answers with the content. */
    fun notModified(request: ServerRequest, etag: String): ServerResponse? {
        val held = request.headers().header(HttpHeaders.IF_NONE_MATCH)
            .flatMap { it.split(',') }
            .map { it.trim().removePrefix("W/") }
        if (held.none { it == etag || it == "*" }) return null
        return ServerResponse.status(HttpStatus.NOT_MODIFIED)
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
            .build()
    }
}
