// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.outbound

import org.springframework.core.io.ResourceLoader
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Reads the bytes behind a catalog or code-list URL, whatever its scheme, after [OutboundUrlGuard]
 * has accepted it. The check is part of every read method, so a caller cannot read without it.
 *
 * Every read is capped at [maxBytes] — the catalog ZIP import's decompressed-size budget, so a
 * source cannot get more in through a URL than it could through an archive.
 */
class OutboundSourceReader(
    private val guard: OutboundUrlGuard,
    private val fetcher: OutboundHttpFetcher,
    private val resourceLoader: ResourceLoader,
    private val maxBytes: Long,
) {
    /** A stored source — a subscribed catalog's manifest or a code list's URL. */
    fun readSource(url: String, headers: Map<String, String>, allowHttp: Boolean): ByteArray {
        guard.checkSource(url, allowHttp)
        return read(url, headers, allowHttp)
    }

    /** A detail or binary [url] that the manifest at [manifestUrl] named, already resolved against it. */
    fun readDerived(url: String, manifestUrl: String, headers: Map<String, String>, allowHttp: Boolean): ByteArray {
        guard.checkDerived(url, manifestUrl, allowHttp)
        return read(url, headers, allowHttp)
    }

    private fun read(url: String, headers: Map<String, String>, allowHttp: Boolean): ByteArray = when (OutboundUrlGuard.schemeOf(url)) {
        "file" -> {
            val path = guard.requireFileUnderRoots(url)
            if (Files.size(path) > maxBytes) throw OutboundFetchException("The file is larger than the $maxBytes-byte limit")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes.coerceAtMost(Int.MAX_VALUE - 8L).toInt()) }
        }

        "classpath" -> {
            val resource = resourceLoader.getResource(url)
            if (!resource.exists()) throw OutboundFetchException("Classpath resource not found: $url")
            resource.contentAsByteArray
        }

        else -> fetcher.get(url, headers, allowHttp, maxBytes)
    }
}
