// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * The template editor on a catalog that cannot be changed here still opens, to show how a template
 * is built, but saves nothing and says so. It used to open as if editable, and every autosave failed
 * with "Not saved: … cannot be changed here" and a Retry that could never succeed.
 */
class TemplateEditorCanSaveHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the editor on a supplied catalog saves nothing and says why, one made here saves`() = fixture {
        lateinit var tenantKey: String
        lateinit var ownTemplate: DocumentTemplate
        given {
            val tenant = tenant("Editor Can Save")
            tenantKey = tenant.id.value
            ownTemplate = template(tenant, "Letter")
            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
                InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo"))).execute()
            }
        }
        whenever {
            listOf(
                "/tenants/$tenantKey/catalogs/epistola-demo/templates/hello-world/variants/default/editor",
                "/tenants/$tenantKey/catalogs/default/templates/${ownTemplate.id}/variants/initial/editor",
            ).map { restTemplate.getForEntity(it, String::class.java).body!! }
        }
        then {
            val (supplied, own) = result<List<String>>()
            assertThat(supplied).contains("\"canSave\": false")
                .contains("data-testid=\"editor-read-only\"").contains("Supplied by Epistola · not saved")
                .contains("Nothing you change here is saved.")
                .`as`("no publish status for a catalog that is not changed here").doesNotContain("data-testid=\"ws-status\"")
            assertThat(own).contains("\"canSave\": true").doesNotContain("data-testid=\"editor-read-only\"")
                .contains("data-testid=\"ws-status\"")
            // The bar names the variant by its title, as the template page does; its key is for the API.
            assertThat(own.substringAfter("data-testid=\"editor-crumb-variant\"").substringBefore("</span>"))
                .endsWith(">Default").contains("title=\"Variant initial\"")
        }
    }

    @Test
    fun `a supplied template's variants can be viewed in the editor, not opened for editing`() = fixture {
        lateinit var tenantKey: String
        lateinit var ownTemplate: DocumentTemplate
        given {
            val tenant = tenant("Editor View Link")
            tenantKey = tenant.id.value
            ownTemplate = template(tenant, "Letter")
            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
                InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo"))).execute()
            }
        }
        whenever {
            listOf(
                "/tenants/$tenantKey/catalogs/epistola-demo/templates/hello-world",
                "/tenants/$tenantKey/catalogs/default/templates/${ownTemplate.id}",
            ).map { restTemplate.getForEntity(it, String::class.java).body!! }
        }
        then {
            val (supplied, own) = result<List<String>>()
            assertThat(supplied).contains("data-testid=\"variant-view-in-editor\"").contains(">View in editor<")
                .contains("/epistola-demo/templates/hello-world/variants/default/editor")
                .doesNotContain("data-testid=\"variant-open-editor\"")
            assertThat(own).contains("data-testid=\"variant-open-editor\"").doesNotContain("data-testid=\"variant-view-in-editor\"")
        }
    }
}
