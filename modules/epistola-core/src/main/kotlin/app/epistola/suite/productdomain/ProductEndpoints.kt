// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import tools.jackson.databind.JsonNode
import java.net.URI
import java.time.Duration

/** Where each Epistola-operated service is, after discovery and fallbacks (ADR 0030). */
data class ProductEndpoints(
    /** The release metadata document. */
    val versionCheck: String,
    /** The installation check; `{id}` is replaced by the installation id. */
    val installationCheck: String,
    val exchange: ExchangeEndpoint,
    val supportHub: HubAddress,
) {
    data class ExchangeEndpoint(val issuer: String, val endpoint: String)

    /** A gRPC target: always TLS when discovered. */
    data class HubAddress(val host: String, val port: Int) {
        override fun toString(): String = "$host:$port"

        companion object {
            /** Parses `host:port`; null when it is not one. */
            fun parse(value: String): HubAddress? {
                val host = value.substringBeforeLast(':', missingDelimiterValue = "")
                val port = value.substringAfterLast(':').toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535 || '/' in host) return null
                return HubAddress(host, port)
            }
        }
    }

    companion object {
        /**
         * What the Suite uses when discovery has nothing for an integration: the addresses it used
         * before the discovery document existed, on [domain]. epistola.app keeps serving them, so a
         * failed discovery is never worse than that.
         */
        fun defaults(domain: ProductDomainProperties): ProductEndpoints = ProductEndpoints(
            versionCheck = "${domain.siteUrl}/.well-known/epistola/releases.json",
            installationCheck = "${domain.siteUrl}/installation/{id}.json",
            exchange = "https://exchange.${domain.host}".let { ExchangeEndpoint(issuer = it, endpoint = it) },
            supportHub = HubAddress("hub.${domain.host}", 443),
        )
    }
}

/**
 * The discovery document's contents after validation: each field is null when the document did not
 * name it or named something the Suite refuses. Stored as is, so a later read never re-trusts it.
 */
data class DiscoveredEndpoints(
    val versionCheck: String? = null,
    val installationCheck: String? = null,
    val exchangeIssuer: String? = null,
    val exchangeEndpoint: String? = null,
    val supportHub: String? = null,
    /**
     * How long this copy stays valid, as the document says, clamped to [MIN_REFRESH]..[MAX_REFRESH];
     * null when it does not say, and the Suite's own default applies.
     */
    val refreshAfter: Duration? = null,
    /** What was refused, for the operator; never shown to end users. */
    val rejected: List<String> = emptyList(),
) {
    /** Applies these over [fallback], one integration at a time. */
    fun over(fallback: ProductEndpoints): ProductEndpoints = fallback.copy(
        versionCheck = versionCheck ?: fallback.versionCheck,
        installationCheck = installationCheck ?: fallback.installationCheck,
        exchange = if (exchangeIssuer != null && exchangeEndpoint != null) {
            ProductEndpoints.ExchangeEndpoint(exchangeIssuer, exchangeEndpoint)
        } else {
            fallback.exchange
        },
        supportHub = supportHub?.let { ProductEndpoints.HubAddress.parse(it) } ?: fallback.supportHub,
    )

    companion object {
        const val SCHEMA_VERSION = 1

        /** Nothing in the document can make installations ask more often than this, */
        val MIN_REFRESH: Duration = Duration.ofMinutes(15)

        /** or stop asking for longer than this. */
        val MAX_REFRESH: Duration = Duration.ofDays(7)

        /**
         * Reads the discovery document, which arrives over the network: an endpoint is taken only
         * when it is `https:` on the product domain or one of its subdomains, and the installation
         * check only with its `{id}`. Unknown fields are ignored, so version 1 can gain optional ones.
         * `refreshAfter` (an ISO-8601 duration) is clamped rather than refused, so the copy is always
         * refreshed at some point.
         */
        fun parse(root: JsonNode, domain: ProductDomainProperties): DiscoveredEndpoints {
            val version = root.path("schemaVersion")
            if (!version.isInt || version.intValue() != SCHEMA_VERSION) {
                throw ProductDiscoveryException("Unsupported discovery document schemaVersion: $version")
            }
            val rejected = mutableListOf<String>()

            fun url(section: String, field: String, needsId: Boolean = false): String? {
                val value = root.path(section).path(field).takeIf { it.isString }?.stringValue() ?: return null
                val reason = when {
                    value.length > MAX_URL -> "longer than $MAX_URL characters"
                    !onDomain(value, domain) -> "not an https URL on ${domain.host}"
                    needsId && "{id}" !in value -> "has no {id}"
                    else -> null
                }
                if (reason != null) rejected += "$section.$field: $reason"
                return value.takeIf { reason == null }?.trimEnd('/')
            }

            val hub = root.path("supportHub").path("endpoint").takeIf { it.isString }?.stringValue()?.let { value ->
                val address = ProductEndpoints.HubAddress.parse(value)
                if (address != null && onDomain("https://${address.host}", domain)) {
                    value
                } else {
                    rejected += "supportHub.endpoint: not a host:port on ${domain.host}"
                    null
                }
            }
            val refreshAfter = root.path("refreshAfter").takeUnless { it.isMissingNode || it.isNull }?.let { node ->
                val parsed = node.takeIf { it.isString }?.stringValue()?.let { runCatching { Duration.parse(it) }.getOrNull() }
                when {
                    parsed == null || parsed < MIN_REFRESH -> MIN_REFRESH.also { rejected += "refreshAfter: '$node' is not a duration of at least $MIN_REFRESH, using $MIN_REFRESH" }
                    parsed > MAX_REFRESH -> MAX_REFRESH.also { rejected += "refreshAfter: '$node' is longer than $MAX_REFRESH, using $MAX_REFRESH" }
                    else -> parsed
                }
            }
            return DiscoveredEndpoints(
                versionCheck = url("versionCheck", "endpoint"),
                installationCheck = url("installationCheck", "endpoint", needsId = true),
                exchangeIssuer = url("exchange", "issuer"),
                exchangeEndpoint = url("exchange", "endpoint"),
                supportHub = hub,
                refreshAfter = refreshAfter,
                rejected = rejected,
            )
        }

        private const val MAX_URL = 512

        private fun onDomain(value: String, domain: ProductDomainProperties): Boolean {
            val uri = runCatching { URI(value.replace("{id}", "id")) }.getOrNull() ?: return false
            val host = uri.host?.lowercase() ?: return false
            return uri.scheme == "https" && uri.userInfo == null && (host == domain.host || host.endsWith(".${domain.host}"))
        }
    }
}

class ProductDiscoveryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
