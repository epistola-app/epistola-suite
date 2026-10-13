// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.discovery.HubEndpoint
import app.epistola.suite.productdomain.ProductEndpoints
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HubEndpointSettingsTest {
    @Test
    fun `a configured endpoint is host and port`() {
        assertThat(SupportProperties.HubProperties(endpoint = "localhost:9090").configuredEndpoint)
            .isEqualTo(ProductEndpoints.HubAddress("localhost", 9090))
        assertThat(SupportProperties.HubProperties().configuredEndpoint).isNull()

        for (value in listOf("localhost", "localhost:0", "https://hub.example:443", "hub.example:http")) {
            assertThatThrownBy { SupportProperties.HubProperties(endpoint = value) }.describedAs(value)
                .hasMessageContaining("epistola.support.hub.endpoint")
        }
    }

    @Test
    fun `a discovered hub is always reached over TLS, whatever URL the hub client passes`() {
        val discovery = ProductHubDiscovery { ProductEndpoints.HubAddress("hub.epistola.app", 443) }

        assertThat(discovery.resolve("https://ignored.example/hub.json"))
            .isEqualTo(HubEndpoint(host = "hub.epistola.app", port = 443, plaintext = false))
    }
}
