// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

class CatalogOrganiseRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun tenantWithStencil(name: String): String {
        val tenant = createTenant(name)
        val letters = CatalogKey.of("letters")
        withMediator {
            SaveFeatureToggle(tenant.id, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, CatalogKey.of("shared"), "Shared").execute()
            CreateStencil(StencilId(StencilKey.of("header"), CatalogId(letters, TenantId(tenant.id))), "Header").execute()
        }
        return tenant.id.value
    }

    @Test
    fun `the page is absent without the relocation toggle`() {
        val tenant = createTenant("Organise off")

        val response = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/organise", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `the page does not require the resource graph to be enabled`() {
        val tenantId = tenantWithStencil("Organise standalone")

        // Relocation used to live inside the graph explorer, so it needed that feature on too.
        val response = restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("<ep-catalog-organise")
    }

    @Test
    fun `a deep link preselects the resource it names`() {
        val tenantId = tenantWithStencil("Organise deep link")

        val response = restTemplate.getForEntity(
            "/tenants/$tenantId/catalogs/organise?resource=stencil:letters:header",
            String::class.java,
        )

        assertThat(response.body).contains("data-preselected=\"stencil:letters:header\"")
    }

    @Test
    fun `only relocatable types are offered`() {
        val tenantId = tenantWithStencil("Organise listing")

        val response = restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise/resources", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // JsonNode has its own map() in Jackson 3, which shadows Kotlin's on Iterable.
        val types = buildList {
            objectMapper.readTree(response.body).path("resources").forEach { add(it.path("type").stringValue()) }
        }
        assertThat(types).isNotEmpty()
        assertThat(types.filterNot { it in setOf("stencil", "attribute", "template") }).isEmpty()
    }

    @Test
    fun `the browser offers only what the tenant can rearrange`() {
        val tenantId = tenantWithStencil("Organise authored only")
        // A system catalog is installed for every tenant and is not the tenant's to rearrange.
        val body = objectMapper.readTree(
            restTemplate.getForEntity("/tenants/$tenantId/catalogs/organise/resources", String::class.java).body,
        )

        val offeredCatalogs = buildList {
            body.path("resources").forEach { add(it.path("catalogKey").stringValue()) }
        }
        assertThat(offeredCatalogs).isNotEmpty()
        assertThat(offeredCatalogs).doesNotContain("system")

        // Nothing listed is read-only, so no resource carries a note saying so; the note is
        // reserved for consequences of moving, not reasons it cannot be moved.
        val notes = buildList {
            body.path("resources").forEach { node -> node.path("note").takeIf { it.isString }?.let { add(it.stringValue()) } }
        }
        assertThat(notes).allSatisfy { assertThat(it).doesNotContainIgnoringCase("read-only") }
    }

    @Test
    fun `preview then execute moves the selection`() {
        val tenantId = tenantWithStencil("Organise move")
        val body = """{"relocations":[{"type":"stencil","catalog":"letters","key":"header","targetCatalog":"shared"}]}"""

        val preview = post(tenantId, "preview", body)
        assertThat(preview.statusCode).isEqualTo(HttpStatus.OK)
        val plan = objectMapper.readTree(preview.body)
        assertThat(plan.path("executable").booleanValue()).isTrue()

        val fingerprint = plan.path("planFingerprint").stringValue()
        val executed = post(
            tenantId,
            "execute",
            """{"relocations":[{"type":"stencil","catalog":"letters","key":"header","targetCatalog":"shared"}],"planFingerprint":"$fingerprint"}""",
        )

        assertThat(executed.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(
            objectMapper.readTree(executed.body).path("relocations").single().path("target").path("catalogKey").stringValue(),
        ).isEqualTo("shared")
    }

    private fun post(tenantId: String, path: String, body: String) = restTemplate.postForEntity(
        "/tenants/$tenantId/catalogs/organise/$path",
        HttpEntity(body, HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }),
        String::class.java,
    )
}
