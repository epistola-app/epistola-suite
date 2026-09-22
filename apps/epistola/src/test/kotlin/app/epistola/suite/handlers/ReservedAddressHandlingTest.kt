// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * An address a moved resource left behind is reserved: published documents naming it still use it.
 * Creating a resource there is refused, and the author has to be told why rather than shown an
 * error page.
 */
class ReservedAddressHandlingTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `creating a stencil at an address a moved stencil left gets a readable error on the slug`() {
        val tenant = createTenant("Reserved address create").id
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute()
            CreateStencil(StencilId(StencilKey.of("header"), CatalogId(letters, TenantId(tenant))), "Header").execute()
            val relocation = ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header").movedTo(CatalogKey.of("shared"))
            val plan = PreviewCatalogResourceMove(tenant, listOf(relocation)).query()
            MoveCatalogResources(tenant, listOf(relocation), plan.planFingerprint).execute()
        }

        val response = restTemplate.postForEntity(
            "/tenants/$tenant/stencils",
            HttpEntity(
                LinkedMultiValueMap<String, String>().apply {
                    add("catalog", letters.value)
                    add("slug", "header")
                    add("name", "Replacement")
                },
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )

        assertThat(response.statusCode.value()).isLessThan(500)
        assertThat(response.body).contains("moved away from letters/header").contains("Choose another key")
    }
}
