// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.service

import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.AuthType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.file.Path

/**
 * Fetches code list entries from a `classpath:`, `file:` or `https:` URL.
 *
 * Mirrors `CatalogClient` (modules/epistola-core/.../catalog/CatalogClient.kt:15)
 * — same `ResourceLoader` + `RestClient` pattern, same scheme allowlist with
 * an opt-in for `http://` via `epistola.codelists.allow-http=true`. Reuses the
 * existing `catalogRestClient` bean so we don't introduce a second HTTP client.
 *
 * The expected payload is a JSON array of `{ code, label, sortOrder?, hidden? }`
 * objects. Returning the labels and ordering as-is from the source lets the
 * source own the curation; the operator decides whether to re-publish or hide
 * specific entries locally afterward.
 */
@Component
class CodeListClient(
    private val catalogRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val resourceLoader: ResourceLoader,
    @Value("\${epistola.codelists.allow-http:false}") private val allowHttp: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val allowedSchemes = buildSet {
        add("https")
        add("file")
        add("classpath")
        if (allowHttp) add("http")
    }

    fun fetchEntries(url: String, authType: AuthType, credential: String?): List<CodeListEntry> {
        validateUrl(url, allowedSchemes)
        logger.debug("Fetching code list entries from {}", url)
        return readLocal(url) ?: fetchHttp(url, authType, credential)
    }

    private fun readLocal(url: String): List<CodeListEntry>? = when {
        url.startsWith("file:") -> {
            val path = Path.of(URI.create(url))
            if (!path.toFile().exists()) throw CodeListFetchException("File not found: $path")
            objectMapper.readValue(path.toFile(), ENTRIES_TYPE)
        }
        url.startsWith("classpath:") -> {
            val resource = resourceLoader.getResource(url)
            if (!resource.exists()) throw CodeListFetchException("Classpath resource not found: $url")
            objectMapper.readValue(resource.contentAsByteArray, ENTRIES_TYPE)
        }
        else -> null
    }

    private fun fetchHttp(url: String, authType: AuthType, credential: String?): List<CodeListEntry> = try {
        catalogRestClient.get()
            .uri(url)
            .applyAuth(authType, credential)
            .retrieve()
            .body(SPRING_ENTRIES_TYPE)
            ?: throw CodeListFetchException("Empty response from: $url")
    } catch (e: RestClientException) {
        // Wrap so the caller (RefreshCodeList) sees a single failure type and
        // can record the message in `last_refresh_error` without leaking
        // Spring's exception hierarchy across module boundaries.
        throw CodeListFetchException("Fetch failed for $url: ${e.message}", e)
    }

    private fun RestClient.RequestHeadersSpec<*>.applyAuth(authType: AuthType, credential: String?): RestClient.RequestHeadersSpec<*> = apply {
        when (authType) {
            AuthType.NONE -> {}
            AuthType.BEARER -> header(HttpHeaders.AUTHORIZATION, "Bearer $credential")
            AuthType.API_KEY -> header("X-API-Key", credential ?: "")
        }
    }

    companion object {
        private val ENTRIES_TYPE = object : TypeReference<List<CodeListEntry>>() {}
        private val SPRING_ENTRIES_TYPE = object : ParameterizedTypeReference<List<CodeListEntry>>() {}

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
