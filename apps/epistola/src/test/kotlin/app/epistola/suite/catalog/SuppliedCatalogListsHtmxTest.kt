// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
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
 * A catalog Epistola supplies (a `classpath:` source, ADR 0032) cannot be changed here, so its lists
 * offer nothing to create (.agents/rules/ui-affordances.md): the bar says why. They used to offer New
 * and Upload, and the form was refused only once filled in. A catalog made here still offers them.
 */
class SuppliedCatalogListsHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val lists = listOf("templates", "themes", "stencils", "images", "fonts", "attributes", "code-lists")

    private val create = Regex(""">\s*(New (template|theme|stencil|attribute|code list)|Upload (image|font))\s*<""", RegexOption.IGNORE_CASE)

    @Test
    fun `a supplied catalog's lists offer nothing to create and say why, one made here offers them`() = fixture {
        lateinit var tenantKey: String
        given {
            val tenant = tenant("Supplied Lists")
            tenantKey = tenant.id.value
            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
                InstallFromCatalog(catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo"))).execute()
            }
        }
        whenever { lists.associateWith { list -> restTemplate.getForEntity("/tenants/$tenantKey/catalogs/epistola-demo/$list", String::class.java).body!! } }
        then {
            val pages = result<Map<String, String>>()
            pages.forEach { (list, page) ->
                assertThat(page).`as`(list).contains("data-testid=\"catalog-version-bar-read-only\"").contains(">Supplied by Epistola<")
                assertThat(create.find(page)?.value).`as`("$list offers to create in a supplied catalog").isNull()
                assertThat(page).`as`("$list says read-only once, in the bar, not on every row").doesNotContain(">Read-only</span>")
            }
            lists.forEach { list ->
                val own = restTemplate.getForEntity("/tenants/$tenantKey/catalogs/default/$list", String::class.java).body!!
                assertThat(create.find(own)).`as`("$list in a catalog made here").isNotNull()
                assertThat(own).doesNotContain("data-testid=\"catalog-version-bar-read-only\"")
            }
            assertThat(pages.getValue("templates")).`as`("the list is the catalog's, not every template").contains("The templates in this catalog.")
        }
    }
}
