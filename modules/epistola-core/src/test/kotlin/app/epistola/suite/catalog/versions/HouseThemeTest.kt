// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

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
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The tenant's house theme: a starting value for catalogs, never read while rendering (ADR 0007, 1a). */
class HouseThemeTest : IntegrationTestBase() {

    private val brand = CatalogKey.of("brand")
    private val house = CatalogDefaultTheme(ThemeKey.of("house"), brand)

    @Test
    fun `a new catalog starts with the house theme, and uses the catalog it lives in`() {
        val tenant = createTenant("House Theme New Catalog")
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateTheme(ThemeId(house.themeKey, CatalogId(brand, TenantId(tenant.id))), "House").execute()
            SetTenantDefaultTheme(tenant.id, house.themeKey, catalogKey = brand).execute()

            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()

            assertThat(GetCatalogThemeSettings(tenant.id, letters).query().defaultTheme).isEqualTo(house)
            assertThat(ListCatalogDependencies(tenant.id, letters).query().map { it.catalogKey }).contains(brand.value)
        }
    }

    @Test
    fun `applying the house theme gives every authored catalog it, and without one nothing changes`() {
        val tenant = createTenant("House Theme Apply")
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            CreateTheme(ThemeId(house.themeKey, CatalogId(brand, TenantId(tenant.id))), "House").execute()

            assertThat(ApplyHouseTheme(tenant.id).execute().applied).`as`("no house theme yet").isEmpty()
            assertThat(GetCatalogThemeSettings(tenant.id, letters).query().defaultTheme).isNull()

            SetTenantDefaultTheme(tenant.id, house.themeKey, catalogKey = brand).execute()
            val result = ApplyHouseTheme(tenant.id).execute()

            assertThat(result.applied).contains(letters, brand)
            assertThat(GetCatalogThemeSettings(tenant.id, letters).query().defaultTheme).isEqualTo(house)
            assertThat(GetCatalogThemeSettings(tenant.id, brand).query().defaultTheme).isEqualTo(house)
        }
    }
}
