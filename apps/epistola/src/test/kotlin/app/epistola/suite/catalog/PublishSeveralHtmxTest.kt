// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Publish several catalogs (`/catalogs/publish`): the catalogs with something to publish, what each
 * becomes and publishes along, and one Publish for the ticked ones, dependencies first.
 */
class PublishSeveralHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` with a theme and `letters` with an invoice using it; nothing published. */
    private fun tenantWithChain(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = shared, name = "Shared").execute()
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), shared)), "Brand").execute()
                    val invoice = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant.id), letters))
                    CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
                    UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice.tenantKey, shared)).execute()
                }
            }
        }
        return tenant
    }

    @Test
    fun `the page lists what has something to publish, ticked, with what each publishes along`() {
        val tenant = tenantWithChain("Publish Several Page")

        val page = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/publish", String::class.java).body!!

        val letterRow = page.substringAfter("data-catalog=\"${testCatalog(tenant.id, letters).address}\"").substringBefore("</tr>")
        assertThat(letterRow).contains("checked", "v1.0.0", "Publishes Shared first.")
        val sharedRow = page.substringAfter("data-catalog=\"${testCatalog(tenant.id, shared).address}\"").substringBefore("</tr>")
        assertThat(sharedRow).contains("checked", "v1.0.0")
        assertThat(page).contains("data-testid=\"publish-several-submit\"")
    }

    @Test
    fun `publishing the ticked catalogs publishes each after what it uses, and says so`() {
        val tenant = tenantWithChain("Publish Several Post")

        val result = post("/tenants/${tenant.id}/catalogs/publish", listOf(testCatalog(tenant.id, letters).address, testCatalog(tenant.id, shared).address)).body!!

        assertThat(result).contains("Shared published as v1.0.0.", "Letters published as v1.0.0.")
        assertThat(result.indexOf("Shared published")).isLessThan(result.indexOf("Letters published"))
        assertThat(withMediator { ListCatalogReleases(testCatalog(tenant.id, letters), CatalogReleaseStatus.RELEASED).query() }.map { it.version }).containsExactly("1.0.0")
        assertThat(withMediator { ListReleaseDependencies(testCatalog(tenant.id, letters), "1.0.0").query() }.map { it.catalogKey to it.releaseVersion })
            .containsExactly(shared.value to "1.0.0")
        assertThat(result).`as`("everything is published now").contains("data-testid=\"publish-nothing\"")
    }

    @Test
    fun `the catalogs list offers it to someone who may publish`() {
        val tenant = tenantWithChain("Publish Several Entry")

        val list = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs", String::class.java).body!!

        assertThat(list).contains("data-testid=\"catalog-publish-several-open\"", "/tenants/${tenant.id}/catalogs/publish")
    }

    private fun post(url: String, catalogs: List<String>) = restTemplate.postForEntity(
        url,
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { catalogs.forEach { add("catalog", it) } },
            HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED },
        ),
        String::class.java,
    )
}
