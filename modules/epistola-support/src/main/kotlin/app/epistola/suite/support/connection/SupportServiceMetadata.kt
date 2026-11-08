// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import tools.jackson.databind.JsonNode
import java.net.URI

/**
 * The support service's OAuth metadata (RFC 8414) after verification: every address in it is on
 * the issuer's origin, so the installation's assertions, tokens and uploads only ever go there.
 */
data class SupportServiceMetadata(
    val issuer: URI,
    val tokenEndpoint: URI,
    val registrationEndpoint: URI,
    val jwksUri: URI?,
    val apiEndpoint: URI,
    val otlpEndpoint: URI?,
    /** `epistola_requests_endpoint`: support requests, protocol v2; null when the service names none. */
    val requestsEndpoint: URI? = null,
    /**
     * `scopes_supported`: the services the support service offers now; null when it does not say.
     * The suite asks only for selected services in this set, and calls a selected service missing
     * from it "no longer offered".
     */
    val scopesSupported: Set<String>? = null,
) {
    companion object {
        /** Where the metadata of [issuer] is read from. */
        fun location(issuer: URI): URI = URI("${issuer.toString().trimEnd('/')}/.well-known/oauth-authorization-server")

        /**
         * Verifies the metadata document read from [location] for [requested]: its `issuer` must
         * equal the URL asked (a trailing slash aside), and every endpoint must be on the issuer's
         * origin — same scheme, host and port. `http:` is refused unless [allowHttp].
         *
         * @throws SupportUnavailableException naming what was refused.
         */
        fun verify(requested: URI, document: JsonNode, allowHttp: Boolean): SupportServiceMetadata {
            val issuer = requireUrl(requested.toString(), "issuer", allowHttp)
            val advertised = document.text("issuer") ?: refuse("the metadata names no issuer")
            if (advertised.trimEnd('/') != issuer.toString().trimEnd('/')) {
                refuse("issuer mismatch: asked '$issuer' but the metadata advertises '$advertised'")
            }

            fun endpoint(field: String): URI? = document.text(field)?.let { value ->
                val url = requireUrl(value, field, allowHttp)
                if (origin(url) != origin(issuer)) refuse("$field '$value' is not on the issuer's origin ('$issuer')")
                url
            }

            fun required(field: String): URI = endpoint(field) ?: refuse("the metadata names no $field")

            return SupportServiceMetadata(
                issuer = URI(issuer.toString().trimEnd('/')),
                tokenEndpoint = required("token_endpoint"),
                registrationEndpoint = required("registration_endpoint"),
                jwksUri = endpoint("jwks_uri"),
                apiEndpoint = URI(required("epistola_api_endpoint").toString().trimEnd('/')),
                otlpEndpoint = endpoint("epistola_otlp_endpoint")?.let { URI(it.toString().trimEnd('/')) },
                requestsEndpoint = endpoint("epistola_requests_endpoint")?.let { URI(it.toString().trimEnd('/')) },
                scopesSupported = document.path("scopes_supported").takeIf { it.isArray }
                    ?.mapNotNull { scope -> scope.takeIf { it.isString }?.stringValue()?.trim()?.takeIf(String::isNotEmpty) }
                    ?.toSet(),
            )
        }

        /** Scheme, host and port, with the scheme's default port made explicit so `:443` compares equal. */
        fun origin(url: URI): String {
            val port = when {
                url.port != -1 -> url.port
                url.scheme == "https" -> 443
                else -> 80
            }
            return "${url.scheme}://${url.host.lowercase()}:$port"
        }

        private fun requireUrl(value: String, what: String, allowHttp: Boolean): URI {
            val url = runCatching { URI(value) }.getOrNull()
            if (url == null || url.host == null || (url.scheme != "https" && url.scheme != "http") || url.userInfo != null) {
                refuse("$what is not an absolute http(s) URL: '$value'")
            }
            if (url.scheme == "http" && !allowHttp) {
                refuse("$what must use HTTPS ('$value'); set epistola.support.allow-http only for a local support service")
            }
            return url
        }

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.stringValue()?.takeIf(String::isNotBlank)

        private fun refuse(reason: String): Nothing = throw SupportUnavailableException("Support service metadata refused: $reason")
    }
}
