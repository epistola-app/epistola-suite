package app.epistola.suite.support

import app.epistola.hub.client.discovery.DEFAULT_DISCOVERY_URL
import app.epistola.hub.client.discovery.HubDiscovery

/**
 * Resolves the OTLP-over-gRPC endpoint URL the telemetry leg ([`epistola-support-telemetry`]) ships
 * logs and metrics to. It is **the hub itself** — `scheme://host:port` of the resolved hub gRPC
 * endpoint (`http` for a plaintext hub, `https` otherwise) — because the hub serves the OTLP collector
 * services on that same gRPC port, so there is no separate endpoint, port, or proxy to configure. The
 * support module owns this derivation so the telemetry feature never needs the hub address configured
 * separately.
 *
 * Resolution may hit the network (`.well-known` discovery), so callers resolve lazily (at startup),
 * not at bean construction.
 */
class HubTelemetryEndpointResolver(
    private val discovery: HubDiscovery,
    private val props: SupportProperties,
) {
    fun resolve(): String {
        val endpoint = discovery.resolve(props.hub.discoveryUrl.ifBlank { DEFAULT_DISCOVERY_URL })
        val scheme = if (endpoint.plaintext) "http" else "https"
        return "$scheme://${endpoint.host}:${endpoint.port}"
    }
}
