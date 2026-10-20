// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * The edit dialogs that send a whole form back (a variant's title and attributes, an attribute's
 * name and allowed values) carry the revision they were opened at, so saving over someone else's
 * change since is refused with a message, not done silently.
 */
class EditDialogRevisionHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val htmx = HttpHeaders().apply { set("HX-Request", "true") }

    private fun get(path: String): String = restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(htmx), String::class.java).body!!

    private fun patch(path: String, vararg fields: Pair<String, String>): ResponseEntity<String> {
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        return restTemplate.exchange(path, HttpMethod.PATCH, HttpEntity(form, headers), String::class.java)
    }

    private fun revisionIn(form: String): String = Regex("""name="expectedRevision" value="(\d+)"""").find(form)!!.groupValues[1]

    private fun tenant(name: String): Tenant {
        lateinit var created: Tenant
        fixture { given { created = tenant(name) } }
        return created
    }

    @Test
    fun `the variant dialog refuses to save over a change made since it was opened`() {
        val tenant = tenant("Variant Revision")
        val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant.id)))
        val variantId = VariantId(VariantKey.of("english"), templateId)
        withMediator {
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            CreateVariant(id = variantId, title = "English", description = null).execute()
        }
        val base = "/tenants/${tenant.id.value}/catalogs/default/templates/invoice/variants/english"
        val opened = revisionIn(get("$base/edit"))

        withMediator { UpdateVariant(variantId = variantId, title = "Changed elsewhere", attributes = emptyMap()).execute() }
        val stale = patch(base, "title" to "Mine", "expectedRevision" to opened)

        assertThat(stale.statusCode.value()).isEqualTo(422)
        assertThat(stale.body).contains("Someone else changed this variant since you opened it")
        assertThat(withMediator { GetVariant(variantId).query() }!!.title).isEqualTo("Changed elsewhere")

        val reopened = revisionIn(get("$base/edit"))
        assertThat(patch(base, "title" to "Mine", "expectedRevision" to reopened).statusCode.is2xxSuccessful).isTrue()
        assertThat(withMediator { GetVariant(variantId).query() }!!.title).isEqualTo("Mine")
    }

    @Test
    fun `the attribute dialog refuses to save over a change made since it was opened, and shows that change`() {
        val tenant = tenant("Attribute Revision")
        val attributeId = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(tenant.id)))
        withMediator { CreateAttributeDefinition(id = attributeId, displayName = "Brand").execute() }
        val base = "/tenants/${tenant.id.value}/catalogs/default/attributes/brand"
        val opened = revisionIn(get("$base/edit"))

        withMediator { UpdateAttributeDefinition(id = attributeId, displayName = "Brand elsewhere").execute() }
        val stale = patch(base, "displayName" to "Mine", "constraintKind" to "free", "expectedRevision" to opened)

        assertThat(stale.body).contains("Someone else changed this attribute since you opened it")
        assertThat(stale.body).`as`("the form shows their version, at its revision").contains("Brand elsewhere").doesNotContain("name=\"expectedRevision\" value=\"$opened\"")
        assertThat(withMediator { GetAttributeDefinition(attributeId).query() }!!.displayName).isEqualTo("Brand elsewhere")
    }

    @Test
    fun `a refused attribute edit keeps what was typed`() {
        val tenant = tenant("Attribute Refusal")
        val attributeId = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(tenant.id)))
        withMediator { CreateAttributeDefinition(id = attributeId, displayName = "Brand").execute() }
        val base = "/tenants/${tenant.id.value}/catalogs/default/attributes/brand"

        // Only spaces passes the browser's `required`.
        val refused = patch(base, "displayName" to "   ", "constraintKind" to "inline", "allowedValues" to "a\nb", "expectedRevision" to revisionIn(get("$base/edit")))

        assertThat(refused.statusCode.value()).isEqualTo(422)
        assertThat(refused.headers.getFirst("HX-Reswap")).`as`("only the error slot swaps; the form stays as typed").isEqualTo("none")
        assertThat(refused.body).contains("id=\"edit-attribute-error\"").doesNotContain("name=\"displayName\"")
    }
}
