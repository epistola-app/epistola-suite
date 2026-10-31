// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/** The template page's API tab: what an application sends to get this template, and whether it can yet. */
class TemplateApplicationTabHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String, setup: (Tenant, CatalogId) -> Unit): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    val catalog = testCatalog(TenantId(tenant.id), letters)
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute().withRequiredDataExample()
                    setup(tenant, catalog)
                }
            }
        }
        return tenant
    }

    private fun tab(tenant: Tenant) = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${tenant.id}/letters/templates/invoice/application", String::class.java)

    @Test
    fun `before anything is published the tab names the template and says there is nothing to get yet`() {
        val tenant = catalog("Application Unpublished") { _, _ -> }

        val response = tab(tenant)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val page = response.body!!
        assertThat(page).contains("data-tab-name=\"application\"").contains("data-testid=\"template-application\"")
        val namespace = withMediator { GetCatalog(testCatalog(tenant.id, letters)).query()!!.namespace }
        assertThat(page).contains("<code>$namespace</code>").contains("<code>letters</code>").contains("<code>invoice</code>")
        assertThat(page).contains("data-testid=\"template-application-unpublished\"")
        assertThat(page).contains("POST /api/tenants/${tenant.id}/documents/generate")
        assertThat(page).`as`("a body per variant, with the template's key").contains("data-testid=\"template-application-variant\"")
            .contains("&quot;templateKey&quot; : &quot;invoice&quot;")
    }

    @Test
    fun `once deployed the tab says where it is live and the body asks through that environment`() {
        val tenant = catalog("Application Live") { tenant, catalog ->
            mediator.releaseNext(catalog)
            val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id))
            CreateEnvironment(test, "Test").execute()
            DeployCatalogRelease(test, catalog, "1.0.0", includeDependencies = true).execute()
        }

        val page = tab(tenant).body!!

        assertThat(page).doesNotContain("data-testid=\"template-application-unpublished\"")
        assertThat(page).contains("data-testid=\"template-application-live\"").contains(">v1.0.0<")
        assertThat(page).contains("&quot;environmentKey&quot; : &quot;test&quot;")
    }
}
