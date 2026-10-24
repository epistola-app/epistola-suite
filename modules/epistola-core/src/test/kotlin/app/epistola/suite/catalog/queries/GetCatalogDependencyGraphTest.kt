// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The tenant's catalogs and their current versions' declared dependencies, matched to the tenant's catalogs. */
class GetCatalogDependencyGraphTest : IntegrationTestBase() {

    @Test
    fun `every catalog is a node and each declared dependency an edge, tied to its release`() {
        val tenant = createTenant("Dependency Graph").id
        val shared = testCatalog(TenantId(tenant), CatalogKey.of("shared"))
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared.key, name = "Shared").execute()
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            mediator.releaseNext(shared)
            declareDependency(tenant, letters, on = shared.key)
        }

        val graph = withMediator { GetCatalogDependencyGraph(tenant).query() }

        assertThat(graph.nodes.map { it.catalogKey }).contains("shared", "letters")
        assertThat(graph.nodes.single { it.catalogKey == "letters" }.identity).endsWith("/letters")
        val edge = graph.edges.single { it.from == "letters" }
        assertThat(edge.to).isEqualTo("shared")
        assertThat(edge.release).`as`("pinned to the highest release, as declaring does").isEqualTo("1.0.0")
        assertThat(graph.edges.map { it.from }).`as`("only declarations, nothing for shared itself").doesNotContain("shared")
    }
}
