// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class VersionCheckClient(
    @Qualifier(VersionCheckConfiguration.VERSION_CHECK_REST_CLIENT)
    private val restClient: RestClient,
) {
    /**
     * Fetches the releases document anonymously: no installation id, no version (not even in the
     * User-Agent). The document is the same for everyone and the Suite evaluates it locally, so
     * security notices never depend on identifying the installation. See docs/outbound-traffic.md.
     */
    fun fetch(url: String): EpistolaReleasesDocument = try {
        val request = request(url)
        restClient.get()
            .uri(request.url)
            .headers { http -> request.headers.forEach { (name, value) -> http.set(name, value) } }
            .retrieve()
            .body(EpistolaReleasesDocument::class.java)
            ?: throw VersionCheckException("Empty response from $url")
    } catch (e: RestClientResponseException) {
        if (e.statusCode == HttpStatus.NOT_FOUND) {
            throw VersionMetadataUnavailableException("Release metadata not found at $url", e)
        }
        throw VersionCheckException("Fetch failed for $url: ${e.message}", e)
    } catch (e: RestClientException) {
        throw VersionCheckException("Fetch failed for $url: ${e.message}", e)
    }

    companion object {
        /** What the version check sends to [url]: nothing that identifies the installation. */
        fun request(url: String) = OutgoingRequest(url, mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT))
    }
}

/** Sent by every call to Epistola: the product, never its version. */
const val USER_AGENT = "epistola-suite"

class VersionCheckException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class VersionMetadataUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
