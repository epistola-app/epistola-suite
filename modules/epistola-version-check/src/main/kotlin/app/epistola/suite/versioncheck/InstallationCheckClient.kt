// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.version.SemVersion
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Calls the installation check. The response is untrusted: it is read up to [MAX_BYTES], parsed as
 * a tree, and only the documented fields are taken, cleaned ([UntrustedResponse]).
 */
class InstallationCheckClient(private val properties: InstallationCheckProperties) {
    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(properties.connectTimeout)
                setReadTimeout(properties.readTimeout)
            },
        )
        .build()
    private val mapper = JsonMapper.builder().build()

    /** The URL and headers a check for [installationId] sends; [suiteVersion] only when shared. */
    fun request(installationId: UUID, suiteVersion: String?): Pair<String, Map<String, String>> {
        val headers = buildMap {
            put("Accept", "application/json")
            put("User-Agent", USER_AGENT)
            if (suiteVersion != null) put("X-Epistola-Suite-Version", suiteVersion)
        }
        return properties.url.replace("{id}", installationId.toString()) to headers
    }

    /** Performs the request; returns the HTTP status and the parsed result for a 200. */
    fun fetch(url: String, headers: Map<String, String>): Pair<Int, InstallationCheckResult?> = try {
        restClient.get()
            .uri(url)
            .headers { http -> headers.forEach { (name, value) -> http.set(name, value) } }
            .exchange { _, response ->
                val status = response.statusCode.value()
                if (status != 200) return@exchange status to null
                val bytes = response.body.use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) throw InstallationCheckException("Response larger than $MAX_BYTES bytes")
                status to parse(mapper.readTree(bytes))
            }
    } catch (e: RestClientException) {
        throw InstallationCheckException("Installation check failed: ${e.message}", e)
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        private val STATUSES = setOf("supported", "expired", "revoked")
        private val ROUTES = setOf("saas", "partner-managed", "self-hosted")

        /** Takes only the documented fields; anything unexpected is dropped, never guessed at. */
        fun parse(root: JsonNode): InstallationCheckResult {
            val installation = root.path("installation")
            if (!installation.isObject) throw InstallationCheckException("Response has no installation object")
            val party = installation.path("supportParty")
            return InstallationCheckResult(
                registered = installation.path("registered").asBoolean(false),
                status = installation.string("status")?.takeIf { it in STATUSES },
                route = installation.string("route")?.takeIf { it in ROUTES },
                supportedUntil = installation.string("supportedUntil")
                    ?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess },
                supportPartyName = UntrustedResponse.text(party.string("name"), max = 100),
                supportPartyUrl = UntrustedResponse.httpsUrl(party.string("url")),
                supportContact = UntrustedResponse.httpsUrl(party.string("supportContact")),
                minVersion = installation.path("versionPolicy").string("minVersion")
                    ?.takeIf { it.length <= 64 && SemVersion.parse(it) != null },
                page = UntrustedResponse.httpsUrl(installation.string("page")),
            )
        }

        private fun JsonNode.string(name: String): String? = path(name).takeIf { it.isString }?.stringValue()
    }
}

class InstallationCheckException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
