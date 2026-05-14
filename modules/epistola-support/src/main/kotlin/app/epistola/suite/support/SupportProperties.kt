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
     * Connection details for the epistola-hub server.
     *
     * @property discoveryUrl Override the discovery URL when self-hosting the
     *   hub. Blank → use the SaaS default baked into the hub client.
     * @property nodeId Explicit per-pod node identifier. Blank/null → auto-detect
     *   from EPISTOLA_NODE_ID / HOSTNAME / POD_NAME / local hostname.
     */
    data class HubProperties(
        val discoveryUrl: String = "",
        val nodeId: String? = null,
    )
}
