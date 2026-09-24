// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * A template page says which catalog it is in, and where that catalog stands.
 *
 * It used to say neither. The back link went to the flat template list, and the only mention of a
 * catalog was "belongs to a subscribed catalog" — which did not name it. Someone could edit for an
 * hour without knowing whether their work was in a released version or waiting to be.
 */
class TemplateCatalogContextTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `an unreleased edit is named on the template page, with the release action`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Template Context")
                withMediator {
                    val catalogKey = CatalogKey.of("ctx-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Context cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
                    // Edited after releasing, which is the state the strip exists to report.
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("later"), catalog), "Later")
                        .execute().withRequiredDataExample()
                }
            }
        }

        val body = page(tenant, "ctx-cat", "invoice")

        assertThat(body).contains("Context cat")
        assertThat(body).contains("Last released v1.0.0")
        assertThat(body).contains("unreleased changes")
        assertThat(body)
            .`as`("releasing is offered where there is something to release")
            .contains("Release catalog")
    }

    @Test
    fun `a template in a catalog with nothing to release is not offered one`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Template Context Clean")
                withMediator {
                    val catalogKey = CatalogKey.of("clean-cat")
                    val catalog = CatalogId(catalogKey, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = catalogKey, name = "Clean cat").execute()
                    CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice")
                        .execute().withRequiredDataExample()
                    ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = catalogKey, version = "1.0.0").execute()
                }
            }
        }

        val body = page(tenant, "clean-cat", "invoice")

        assertThat(body).contains("Last released v1.0.0")
        assertThat(body).doesNotContain("unreleased changes")
        assertThat(body)
            .`as`("nothing has changed, so there is no release to cut")
            .doesNotContain("Release catalog")
    }

    private fun page(tenant: Tenant, catalog: String, template: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/templates/$catalog/$template", String::class.java)
        .body!!
}
