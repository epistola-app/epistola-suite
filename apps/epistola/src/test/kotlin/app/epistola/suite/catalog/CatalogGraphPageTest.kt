// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * The catalog dependency drawing: the whole tenant's, linked from the catalog list, and on a
 * catalog's Dependencies page narrowed to that catalog, with it highlighted.
 */
class CatalogGraphPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun page(path: String): String = restTemplate.getForEntity(path, String::class.java).body!!

    /** A tenant where `letters` uses `shared`, and `unrelated` uses nothing. */
    private fun tenantWithDependency(): Tenant {
        lateinit var created: Tenant
        fixture {
            given {
                created = tenant("Graph Page")
                withMediator {
                    listOf("shared", "letters", "unrelated").forEach { CreateCatalog(tenantKey = created.id, id = CatalogKey.of(it), name = it.replaceFirstChar { c -> c.uppercase() }).execute() }
                    val shared = testCatalog(TenantId(created.id), CatalogKey.of("shared"))
                    CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
                    mediator.releaseNext(shared)
                    val target = GetCatalog(testCatalog(created.id, shared.key)).query()!!
                    UpdateCatalogDependencies(testCatalog(created.id, CatalogKey.of("letters")), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0"))).execute()
                }
            }
        }
        return created
    }

    @Test
    fun `the tenant's drawing has every catalog and an arrow for each dependency, and the list links to it`() {
        val tenant = tenantWithDependency()

        val graph = page("/tenants/${tenant.id.value}/catalogs/graph")
        assertThat(graph).contains("data-testid=\"catalog-graph\"")
        listOf("shared", "letters", "unrelated").forEach { assertThat(graph).contains("data-catalog=\"${tenant.id.value}/$it\"") }
        assertThat(graph).contains("data-testid=\"catalog-graph-edge\"").contains(">v1.0.0<")
        assertThat(graph).`as`("each catalog opens its own Dependencies page").contains("href=\"/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/letters/dependencies\"")

        assertThat(page("/tenants/${tenant.id.value}/catalogs")).contains("href=\"/tenants/${tenant.id.value}/catalogs/graph\"")
    }

    @Test
    fun `a catalog's Dependencies page draws what it uses and what uses it, and nothing else`() {
        val tenant = tenantWithDependency()

        val shared = page("/tenants/${tenant.id.value}/catalogs/shared/dependencies")
            .substringAfter("data-testid=\"catalog-dependency-graph\"").substringBefore("data-testid=\"catalog-dependents\"")
        assertThat(shared).contains("data-catalog=\"${tenant.id.value}/shared\"").`as`("used by").contains("data-catalog=\"${tenant.id.value}/letters\"")
        assertThat(shared).doesNotContain("data-catalog=\"${tenant.id.value}/unrelated\"")
    }
}
