// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The tenant's house theme: a starting value for catalogs, never read while rendering (ADR 0007, 1a). */
class HouseThemeTest : IntegrationTestBase() {

    private val brand = CatalogKey.of("brand")
    private val houseKey = ThemeKey.of("house")

    private fun house(tenant: app.epistola.suite.common.ids.TenantKey) = CatalogDefaultTheme(houseKey, testCatalog(tenant, brand))

    @Test
    fun `a new catalog starts with the house theme, and uses the catalog it lives in`() {
        val tenant = createTenant("House Theme New Catalog")
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House").execute()
            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()

            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()

            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters)).query().defaultTheme).isEqualTo(house(tenant.id))
            assertThat(ListCatalogDependencies(testCatalog(tenant.id, letters)).query().map { it.catalogKey }).contains(brand.value)
        }
    }

    @Test
    fun `applying the house theme gives every authored catalog it, and without one nothing changes`() {
        val tenant = createTenant("House Theme Apply")
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House").execute()

            assertThat(ApplyHouseTheme(tenant.id).execute().applied).`as`("no house theme yet").isEmpty()
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters)).query().defaultTheme).isNull()

            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()
            val result = ApplyHouseTheme(tenant.id).execute()

            assertThat(result.applied.map { it.key }).contains(letters, brand)
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters)).query().defaultTheme).isEqualTo(house(tenant.id))
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, brand)).query().defaultTheme).isEqualTo(house(tenant.id))
        }
    }
}
