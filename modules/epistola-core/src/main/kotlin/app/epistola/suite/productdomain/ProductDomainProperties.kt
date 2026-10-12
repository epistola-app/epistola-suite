// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.productdomain

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where Epistola-operated services are found (ADR 0030). Every integration with Epistola — the
 * version check, the installation check, Exchange and the support hub — reads its endpoint from the
 * discovery document on this domain, unless its own `endpoint` setting names one.
 */
@ConfigurationProperties(prefix = "epistola")
data class ProductDomainProperties(
    /**
     * A host name, optionally with a port: never a URL, so it cannot be set to plain `http:`.
     * Local development and tests that point at a plain-`http` service set that integration's
     * `endpoint` instead.
     */
    val productDomain: String = DEFAULT,
) {
    init {
        require(DOMAIN.matches(productDomain)) {
            "epistola.product-domain must be a host name, optionally with a port, such as 'epistola.app' " +
                "(no scheme, no path); got '$productDomain'"
        }
    }

    /** The host, without the port: what discovered endpoints are matched against. */
    val host: String get() = productDomain.substringBefore(':').lowercase()

    /** The site's root, for links a person follows (the support badge, its help pages). */
    val siteUrl: String get() = "https://$productDomain"

    /** The discovery document. */
    val discoveryUrl: String get() = "$siteUrl/.well-known/epistola.json"

    companion object {
        const val DEFAULT = "epistola.app"
        private val DOMAIN = Regex(
            """^(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:[0-9]{1,5})?$""",
        )
    }
}
