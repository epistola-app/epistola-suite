// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * The template detail page on the 2.0 model: no versions, each variant says whether it is ready,
 * and "Mark ready" (per variant and for the whole template) is offered only while something is not
 * ready.
 */
class VariantReadyHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `detail page shows a new variant as not ready and offers marking it`() = fixture {
        lateinit var tenant: Tenant
        lateinit var template: DocumentTemplate

        given {
            tenant = tenant("Variant Ready Detail")
            template = template(tenant, "Letter").withRequiredDataExample()
        }

        whenever {
            restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/default/templates/${template.id}", String::class.java)
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("data-testid=\"variant-not-ready\"").contains(">Not checked yet<").doesNotContain(">Not ready<")
            assertThat(body).contains("data-testid=\"variant-mark-ready\"")
            assertThat(body).contains("data-testid=\"template-mark-ready\"")
            // No versions any more: no history, no publish, no discard.
            assertThat(body).doesNotContain("Version history")
            assertThat(body).doesNotContain("/draft/publish")
            assertThat(body).doesNotContain("/draft/discard")
        }
    }

    @Test
    fun `POST variant ready marks it and withdraws both actions`() = fixture {
        lateinit var tenant: Tenant
        lateinit var template: DocumentTemplate

        given {
            tenant = tenant("Variant Ready Mark")
            template = template(tenant, "Letter").withRequiredDataExample()
        }

        whenever {
            val variant = variantsOf(tenant, template).single()
            postHtmx("/tenants/${tenant.id}/catalogs/default/templates/${template.id}/variants/${variant.id}/ready")
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body).contains("data-testid=\"variant-ready\"")
            assertThat(body).doesNotContain("data-testid=\"variant-mark-ready\"")
            assertThat(body).doesNotContain("data-testid=\"template-mark-ready\"")
            assertThat(variantsOf(tenant, template).single().ready).isTrue()
        }
    }

    @Test
    fun `POST template ready marks every variant`() = fixture {
        lateinit var tenant: Tenant
        lateinit var template: DocumentTemplate

        given {
            tenant = tenant("Template Ready Mark")
            template = template(tenant, "Letter").withRequiredDataExample()
            variant(tenant, template, "Second")
        }

        whenever {
            postHtmx("/tenants/${tenant.id}/catalogs/default/templates/${template.id}/ready")
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).doesNotContain("data-testid=\"variant-not-ready\"")
            assertThat(variantsOf(tenant, template)).hasSize(2).allMatch { it.ready }
        }
    }

    @Test
    fun `a refused mark shows the reason in the variants section`() = fixture {
        lateinit var tenant: Tenant
        lateinit var template: DocumentTemplate

        given {
            tenant = tenant("Variant Ready Refused")
            template = template(tenant, "Letter").withRequiredDataExample()
            // An instance of a stencil the catalog does not have: the model cannot be released.
            val variantId = VariantId(VariantKey.INITIAL, TemplateId(template.id, CatalogId.default(TenantId(tenant.id))))
            UpdateVariantContent(variantId = variantId, templateModel = modelUsingMissingStencil()).execute()
        }

        whenever {
            postHtmx("/tenants/${tenant.id}/catalogs/default/templates/${template.id}/ready")
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("alert alert-error")
            assertThat(variantsOf(tenant, template)).noneMatch { it.ready }
        }
    }

    private fun modelUsingMissingStencil(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "instance" to Node(id = "instance", type = "stencil", slots = emptyList(), props = mapOf("stencilKey" to "missing-stencil")),
        ),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("instance"))),
        themeRef = ThemeRef.Inherit,
    )

    private fun variantsOf(tenant: Tenant, template: DocumentTemplate) = withMediator {
        GetVariantSummaries(TemplateId(template.id, CatalogId.default(TenantId(tenant.id)))).query()
    }

    private fun postHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.postForEntity(url, HttpEntity<Void>(headers), String::class.java)
    }
}
