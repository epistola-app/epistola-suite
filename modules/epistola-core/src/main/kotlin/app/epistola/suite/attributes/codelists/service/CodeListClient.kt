// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.service

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.outbound.OutboundSourceReader
import app.epistola.suite.catalog.outbound.OutboundUrlGuard
import app.epistola.suite.catalog.outbound.OutboundUrlRejectedException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * Fetches code list entries from a `file:` or `https:` URL.
 *
 * Reads through the same [OutboundSourceReader] as `CatalogClient`, so the same rules apply: `file:`
 * only inside `epistola.catalog.file-roots`, network sources only on public addresses (see
 * [app.epistola.suite.catalog.outbound.OutboundUrlGuard]), and `http://` only with
 * `epistola.codelists.allow-http=true`. `classpath:` is not a code-list source: nothing ships one,
 * and it was only ever reachable by typing it.
 *
 * The expected payload is a JSON array of `{ code, label, sortOrder?, hidden? }`
 * objects. Returning the labels and ordering as-is from the source lets the
 * source own the curation; the operator decides whether to re-publish or hide
 * specific entries locally afterward.
 */
@Component
class CodeListClient(
    private val sourceReader: OutboundSourceReader,
    private val guard: OutboundUrlGuard,
    private val objectMapper: ObjectMapper,
    @Value("\${epistola.codelists.allow-http:false}") private val allowHttp: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Refuses a source URL a user is about to store, as a [ValidationException] on `sourceUrl` that
     * every surface renders as a field error. [fetchEntries] checks again before every read.
     */
    fun requireAllowedSourceUrl(url: String) {
        try {
            validateUrl(url, SOURCE_SCHEMES)
            guard.checkSource(url, allowHttp)
        } catch (e: OutboundUrlRejectedException) {
            throw ValidationException("sourceUrl", e.message, ValidationCode.SOURCE_URL_NOT_ALLOWED)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("sourceUrl", e.message ?: "The code list URL is not valid", ValidationCode.SOURCE_URL_NOT_ALLOWED)
        }
    }

    fun fetchEntries(url: String, authType: AuthType, credential: String?): List<CodeListEntry> {
        logger.debug("Fetching code list entries from {}", url)
        return try {
            validateUrl(url, SOURCE_SCHEMES)
            guard.checkSource(url, allowHttp)
            val bytes = sourceReader.readSource(url, authType.headers(credential), allowHttp)
            objectMapper.readValue(bytes, ENTRIES_TYPE)
        } catch (e: CodeListFetchException) {
            throw e
        } catch (e: Exception) {
            // One failure type for the caller (RefreshCodeList), which records a generic message
            // and logs this one. Transport, refusal, status and parse failures all land here.
            throw CodeListFetchException("Fetch failed for $url: ${e.message}", e)
        }
    }

    companion object {
        private val ENTRIES_TYPE = object : TypeReference<List<CodeListEntry>>() {}

        /** What a code-list source may be. `http` is further gated by `allow-http`. */
        private val SOURCE_SCHEMES = setOf("https", "http", "file", "classpath")

        fun validateUrl(url: String, allowedSchemes: Set<String> = setOf("https", "file", "classpath")) {
            require(url.substringAfterLast(".").equals("json", ignoreCase = true)) {
                "Code list URLs must point to .json files"
            }
            val scheme = url.substringBefore(":")
            require(scheme in allowedSchemes) {
                "Unsupported URL scheme: $scheme. Allowed: $allowedSchemes"
            }
            if (url.startsWith("file:")) {
                val uri = URI.create(url)
                val rawPath = uri.path ?: ""
                require(!rawPath.contains("..")) { "Path traversal not allowed" }
            }
        }
    }
}

class CodeListFetchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
