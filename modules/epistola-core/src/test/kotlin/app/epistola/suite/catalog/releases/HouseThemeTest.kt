// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
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
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()

            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()

            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters), catalogVersion = null).query().defaultTheme).isEqualTo(house(tenant.id))
            assertThat(ListCatalogDependencies(testCatalog(tenant.id, letters), catalogVersion = null).query().map { it.catalogKey }).contains(brand.value)
        }
    }

    @Test
    fun `applying the house theme gives every authored catalog it, and without one nothing changes`() {
        val tenant = createTenant("House Theme Apply")
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House", catalogVersion = null).execute()

            assertThat(ApplyHouseTheme(tenant.id).execute().applied).`as`("no house theme yet").isEmpty()
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters), catalogVersion = null).query().defaultTheme).isNull()

            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()
            val result = ApplyHouseTheme(tenant.id).execute()

            assertThat(result.applied.map { it.key }).contains(letters, brand)
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, letters), catalogVersion = null).query().defaultTheme).isEqualTo(house(tenant.id))
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, brand), catalogVersion = null).query().defaultTheme).isEqualTo(house(tenant.id))
        }
    }

    @Test
    fun `applying a house theme from another catalog stops declaring the catalog the replaced one came from, unless something else uses it`() {
        val tenant = createTenant("House Theme Replaced")
        val brandNew = CatalogKey.of("brand-new")
        val newKey = ThemeKey.of("house-new")
        val letters = CatalogKey.of("letters")
        val memos = CatalogKey.of("memos")
        fun declared(catalog: CatalogKey) = ListCatalogDependencies(testCatalog(tenant.id, catalog), catalogVersion = null).query().map { it.catalogKey }
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House", catalogVersion = null).execute()
            // Before any house theme, so it uses nothing of Brand.
            CreateCatalog(tenantKey = tenant.id, id = brandNew, name = "Brand New").execute()
            CreateTheme(ThemeId(newKey, testCatalog(TenantId(tenant.id), brandNew)), "House New", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()
            // Both start with the house theme, so both declare Brand; Memos also gives a template Brand's theme.
            CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
            CreateCatalog(tenantKey = tenant.id, id = memos, name = "Memos").execute()
            val memo = TemplateId(TemplateKey.of("memo"), testCatalog(tenant.id, memos))
            CreateDocumentTemplate(memo, "Memo", catalogVersion = null).execute()
            UpdateDocumentTemplate(memo, themeId = houseKey, themeCatalog = testCatalog(tenant.id, brand), catalogVersion = null).execute()
            assertThat(declared(letters)).contains(brand.value)

            SetTenantDefaultTheme(tenant.id, themeId = newKey, catalog = testCatalog(tenant.id, brandNew)).execute()
            ApplyHouseTheme(tenant.id).execute()

            assertThat(declared(letters)).`as`("nothing in Letters uses Brand any more").contains(brandNew.value, "system").doesNotContain(brand.value)
            assertThat(declared(memos)).`as`("Memo's template still uses Brand's theme").contains(brandNew.value, brand.value)
        }
    }

    @Test
    fun `a house theme moved to a catalog made while the old one was the house theme applies, not refused as catalogs using each other`() {
        val tenant = createTenant("House Theme Moved")
        val brandNew = CatalogKey.of("brand-new")
        val newKey = ThemeKey.of("house-new")
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = brand, name = "Brand").execute()
            CreateTheme(ThemeId(houseKey, testCatalog(TenantId(tenant.id), brand)), "House", catalogVersion = null).execute()
            SetTenantDefaultTheme(tenant.id, themeId = houseKey, catalog = testCatalog(tenant.id, brand)).execute()
            // Made under the old house theme, so it starts declaring Brand.
            CreateCatalog(tenantKey = tenant.id, id = brandNew, name = "Brand New").execute()
            CreateTheme(ThemeId(newKey, testCatalog(TenantId(tenant.id), brandNew)), "House New", catalogVersion = null).execute()

            SetTenantDefaultTheme(tenant.id, themeId = newKey, catalog = testCatalog(tenant.id, brandNew)).execute()
            val result = ApplyHouseTheme(tenant.id).execute()

            assertThat(result.applied.map { it.key }).contains(brand, brandNew)
            assertThat(GetCatalogThemeSettings(testCatalog(tenant.id, brand), catalogVersion = null).query().defaultTheme)
                .isEqualTo(CatalogDefaultTheme(newKey, testCatalog(tenant.id, brandNew)))
            assertThat(ListCatalogDependencies(testCatalog(tenant.id, brandNew), catalogVersion = null).query().map { it.catalogKey })
                .`as`("its own theme replaced Brand's").doesNotContain(brand.value)
        }
    }
}
