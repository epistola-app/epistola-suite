// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.suite.productdomain.ProductEndpoints
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Top-level config for the commercial support tier. Off by default in OSS
 * deployments; flip [enabled] to wire the epistola-hub integration.
 */
@ConfigurationProperties(prefix = "epistola.support")
data class SupportProperties(
    val enabled: Boolean = false,
    val hub: HubProperties = HubProperties(),
) {
    /**
     * Connection details for the epistola-hub server.
     *
     * @property endpoint The hub's gRPC endpoint as `host:port`, for local development
     *   (`localhost:9090`, plaintext) or a cluster-internal hub. Blank → found through the product
     *   domain's discovery document (ADR 0030), always over TLS.
     * @property plaintext Disable TLS on a configured [endpoint]. Local dev only — never set in
     *   production; a discovered hub is always TLS.
     * @property nodeId Per-pod node identifier reported to the hub.
     *   Blank/null → auto-detect from EPISTOLA_NODE_ID / HOSTNAME / POD_NAME
     *   / local hostname.
     */
    data class HubProperties(
        val endpoint: String = "",
        val plaintext: Boolean = false,
        val nodeId: String? = null,
    ) {
        init {
            require(endpoint.isBlank() || ProductEndpoints.HubAddress.parse(endpoint) != null) {
                "epistola.support.hub.endpoint must be host:port, such as localhost:9090; got '$endpoint'"
            }
        }

        /** The configured endpoint; null when the hub is to be discovered. */
        val configuredEndpoint: ProductEndpoints.HubAddress? get() = endpoint.takeIf { it.isNotBlank() }?.let { ProductEndpoints.HubAddress.parse(it) }
    }
}
