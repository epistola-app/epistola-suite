// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.discovery.HubDiscovery
import app.epistola.hub.client.discovery.HubEndpoint
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HubTelemetryEndpointResolverTest {
    private fun resolverFor(endpoint: HubEndpoint) = HubTelemetryEndpointResolver(HubDiscovery.static(endpoint), SupportProperties())

    @Test
    fun `a plaintext hub resolves to an http endpoint`() {
        val resolved = resolverFor(HubEndpoint(host = "hub.local", port = 9090, plaintext = true)).resolve()
        assertThat(resolved).isEqualTo("http://hub.local:9090")
    }

    @Test
    fun `a TLS hub resolves to an https endpoint`() {
        val resolved = resolverFor(HubEndpoint(host = "hub.example", port = 443, plaintext = false)).resolve()
        assertThat(resolved).isEqualTo("https://hub.example:443")
    }
}
