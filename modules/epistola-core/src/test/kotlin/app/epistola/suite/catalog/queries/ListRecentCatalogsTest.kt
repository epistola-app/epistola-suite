// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The catalogs changed most recently, for the tenant's side nav. */
class ListRecentCatalogsTest : IntegrationTestBase() {

    @Test
    fun `the most recently changed catalogs come first, as many as asked, without the system catalog`() {
        val tenant = createTenant("recent-catalogs").id
        withMediator {
            listOf("alpha", "beta", "gamma").forEach { CreateCatalog(tenant, CatalogKey.of(it), it.replaceFirstChar(Char::uppercase)).execute() }
            // Alpha, created first, changes last.
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(CatalogKey.of("alpha"), TenantId(tenant))), "Brand").execute()
        }

        val recent = withMediator { ListRecentCatalogs(tenant, limit = 2).query() }

        assertThat(recent.map { it.id.value }).containsExactly("alpha", "gamma")
        assertThat(withMediator { ListRecentCatalogs(tenant, limit = 10).query() }.map { it.id.value })
            .`as`("the system catalog is left out").doesNotContain("system")
    }
}
