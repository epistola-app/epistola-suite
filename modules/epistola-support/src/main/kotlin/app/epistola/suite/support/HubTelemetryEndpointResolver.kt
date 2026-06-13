package app.epistola.suite.support

import app.epistola.hub.client.discovery.DEFAULT_DISCOVERY_URL
import app.epistola.hub.client.discovery.HubDiscovery

/**
 * Resolves the OTLP/HTTP base URL the telemetry leg ([`epistola-support-telemetry`]) ships logs and
 * metrics to. The support module owns this so the telemetry feature never needs the hub's address
 * configured separately:
 *
 *  - if [SupportProperties.HubProperties.telemetryEndpoint] is set, it wins (a future dedicated
 *    collector endpoint);
 *  - otherwise the endpoint is **the hub itself** — `scheme://host:port` of the resolved hub
 *    endpoint — which proxies OTLP onward for now.
 *
 * Resolution may hit the network (`.well-known` discovery), so callers resolve lazily (at startup),
 * not at bean construction.
 */
class HubTelemetryEndpointResolver(
    private val discovery: HubDiscovery,
    private val props: SupportProperties,
) {
    fun resolve(): String {
        val override = props.hub.telemetryEndpoint
        if (override.isNotBlank()) return override.trimEnd('/')
        val endpoint = discovery.resolve(props.hub.discoveryUrl.ifBlank { DEFAULT_DISCOVERY_URL })
        val scheme = if (endpoint.plaintext) "http" else "https"
        return "$scheme://${endpoint.host}:${endpoint.port}"
    }
}
