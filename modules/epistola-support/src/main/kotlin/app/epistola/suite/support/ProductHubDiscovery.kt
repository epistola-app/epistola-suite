// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support

import app.epistola.hub.client.discovery.HubDiscovery
import app.epistola.hub.client.discovery.HubEndpoint
import app.epistola.suite.productdomain.ProductEndpoints

/**
 * Finds the hub through product discovery (ADR 0030) rather than the hub client's own `hub.json`.
 * The URL the hub client passes in is ignored. A discovered hub is always reached over TLS.
 */
class ProductHubDiscovery(private val discovered: () -> ProductEndpoints.HubAddress) : HubDiscovery() {
    override fun resolve(url: String): HubEndpoint = discovered().let { hub -> HubEndpoint(host = hub.host, port = hub.port, plaintext = false) }
}
