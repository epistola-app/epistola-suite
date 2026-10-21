// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * A subscribed catalog cannot be changed here, so its lists offer nothing to create
 * (.agents/rules/ui-affordances.md): the bar says why. They used to offer New and Upload, and the
 * form was refused only once filled in. An authored catalog's lists still offer them.
 */
class SubscribedCatalogListsHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val lists = listOf("templates", "themes", "stencils", "images", "fonts", "attributes", "code-lists")

    private val create = Regex(""">\s*(New (Template|Theme|Stencil|Attribute|Code List)|Upload (Image|Font))\s*<""")

    @Test
    fun `a subscribed catalog's lists offer nothing to create, an authored one's do`() = fixture {
        lateinit var tenantKey: String
        given {
            val tenant = tenant("Subscribed Lists")
            tenantKey = tenant.id.value
            withMediator {
                RegisterCatalog(tenantKey = tenant.id, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute()
                InstallFromCatalog(tenantKey = tenant.id, catalogKey = CatalogKey.of("epistola-demo")).execute()
            }
        }
        whenever { lists.associateWith { list -> restTemplate.getForEntity("/tenants/$tenantKey/catalogs/epistola-demo/$list", String::class.java).body!! } }
        then {
            val pages = result<Map<String, String>>()
            pages.forEach { (list, page) ->
                assertThat(page).`as`(list).contains("Subscribed: it can't be edited here")
                assertThat(create.find(page)?.value).`as`("$list offers to create in a subscribed catalog").isNull()
            }
            lists.forEach { list ->
                val authored = restTemplate.getForEntity("/tenants/$tenantKey/catalogs/default/$list", String::class.java).body!!
                assertThat(create.find(authored)).`as`("$list in the authored catalog").isNotNull()
            }
            assertThat(pages.getValue("templates")).`as`("the list is the catalog's, not every template").contains("The templates in this catalog.")
        }
    }
}
