// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
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
 * Publish, for people who are not technical (docs/catalog-workspace-navigation.md): the dialog says
 * what changes and what that means, picks the number from the kind of change, and marks the changes
 * ready itself; it offers nothing when there is nothing to publish.
 */
class PublishDialogHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    /** Letters published as 1.0.0 with a code list (en, nl); its work is 1.0.1. */
    private fun published(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    CreateCodeList(
                        id = codeList(tenant),
                        displayName = "Languages",
                        sourceType = CodeListSource.INLINE,
                        entries = listOf(CodeListEntry("en", "English"), CodeListEntry("nl", "Dutch")),
                    ).execute()
                    mediator.releaseNext(CatalogId(letters, TenantId(tenant.id)))
                }
            }
        }
        return tenant
    }

    private fun codeList(tenant: Tenant) = CodeListId(CodeListKey.of("languages"), CatalogId(letters, TenantId(tenant.id)))

    @Test
    fun `an addition is named, said to keep everything working, and published as the next minor`() {
        val tenant = published("Publish Additive")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant.id))), "Invoice")
                .execute().withRequiredDataExample()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains(">Publish Letters<")
        assertThat(dialog).contains("Added").contains("<strong>Invoice</strong>").contains("(Template)")
        assertThat(dialog).contains("data-testid=\"publish-impact-additive\"")
        assertThat(dialog).contains("It will be published as <strong>v1.1.0</strong>")
        assertThat(dialog).contains("data-testid=\"release-submit\"")
        assertThat(dialog).`as`("ready marks are not the person's problem").doesNotContain("data-testid=\"release-blocked\"")
    }

    @Test
    fun `a breaking change is warned about with its reason, and published as the next major`() {
        val tenant = published("Publish Breaking")
        withMediator {
            UpdateCodeList(codeList(tenant), displayName = "Languages", entries = listOf(CodeListEntry("en", "English"))).execute()
        }

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains("data-testid=\"publish-impact-breaking\"").contains("This can break what is connected to it")
        assertThat(dialog).contains("Code list Languages: entry &#39;nl&#39; removed")
        assertThat(dialog).contains("It will be published as <strong>v2.0.0</strong>")
    }

    @Test
    fun `with nothing changed there is nothing to publish`() {
        val tenant = published("Publish Nothing")

        val dialog = getHtmx("/tenants/${tenant.id}/catalogs/letters/release").body!!

        assertThat(dialog).contains("data-testid=\"publish-nothing\"")
        assertThat(dialog).doesNotContain("data-testid=\"release-submit\"")
    }

    @Test
    fun `publishing marks the changes ready itself and publishes under the picked number`() {
        val tenant = published("Publish Submit")
        withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant.id))), "Invoice")
                .execute().withRequiredDataExample()
        }

        postForm("/tenants/${tenant.id}/catalogs/letters/release", mapOf("version" to "1.1.0"))

        val released = withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.RELEASED).query().map { it.version } }
        assertThat(released).contains("1.1.0")
    }

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }
}
