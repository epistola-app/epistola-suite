// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

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
     * Connection details for the epistola-hub server. Two resolution modes:
     *
     * 1. **Direct endpoint** (set [host] + [port]): skip `.well-known`
     *    discovery entirely. Use for local development (`localhost:9090`
     *    plaintext) or cluster-internal hub deployments.
     * 2. **`.well-known` discovery** (leave [host] blank): fetch the
     *    discovery JSON from [discoveryUrl] (or the SaaS default).
     *
     * @property discoveryUrl `.well-known` URL used when [host] is blank.
     *   Blank → the hub client's `DEFAULT_DISCOVERY_URL` (the SaaS endpoint).
     * @property host Direct hub gRPC host. Blank → use discovery.
     * @property port Direct hub gRPC port. Ignored when [host] is blank.
     * @property plaintext Disable TLS on the direct gRPC connection.
     *   Local dev only — never set in production.
     * @property nodeId Per-pod node identifier reported to the hub.
     *   Blank/null → auto-detect from EPISTOLA_NODE_ID / HOSTNAME / POD_NAME
     *   / local hostname.
     */
    data class HubProperties(
        val discoveryUrl: String = "",
        val host: String = "",
        val port: Int = 0,
        val plaintext: Boolean = false,
        val nodeId: String? = null,
    )
}
