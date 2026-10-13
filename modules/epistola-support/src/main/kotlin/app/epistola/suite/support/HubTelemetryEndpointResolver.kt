// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.discovery.HubDiscovery

/**
 * Resolves the OTLP-over-gRPC endpoint URL the telemetry leg ([`epistola-support-telemetry`]) ships
 * logs and metrics to. It is **the hub itself** — `scheme://host:port` of the resolved hub gRPC
 * endpoint (`http` for a plaintext hub, `https` otherwise) — because the hub serves the OTLP collector
 * services on that same gRPC port, so there is no separate endpoint, port, or proxy to configure. The
 * support module owns this derivation so the telemetry feature never needs the hub address configured
 * separately.
 *
 * Resolution may hit the network (product discovery), so callers resolve lazily (at startup), not
 * at bean construction.
 */
class HubTelemetryEndpointResolver(
    private val discovery: HubDiscovery,
) {
    fun resolve(): String {
        // Both implementations the support module wires ignore the URL argument (SupportConfiguration).
        val endpoint = discovery.resolve()
        val scheme = if (endpoint.plaintext) "http" else "https"
        return "$scheme://${endpoint.host}:${endpoint.port}"
    }
}
