// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class CatalogScopedRoutingTest {
    private val routing = CatalogScopedRouting()

    @Test
    fun `an application without catalog pages starts, and its catalog routers route nothing`() {
        val request = ServerRequest.create(MockHttpServletRequest("GET", "/tenants/acme/catalogs/letters/browse"), emptyList())

        assertThat(routing.catalogRoutesByKey(emptyList()).route(request)).isEmpty()
        assertThat(routing.catalogRoutesByAddress(emptyList()).route(request)).isEmpty()
    }
}
