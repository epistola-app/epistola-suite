package app.epistola.suite.themes

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThemeQueriesTest : IntegrationTestBase() {

    @Test
    fun `GetTheme returns the theme`(): Unit = withMediator {
        val tenant = createTenant("Theme Get")
        val catalogId = CatalogId.default(TenantId(tenant.id))
        val themeId = ThemeId(ThemeKey.of("brand"), catalogId)
        val created = CreateTheme(id = themeId, name = "Brand Theme", description = "Corporate look").execute()

        val found = GetTheme(id = themeId).query()

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(created.id)
        assertThat(found.tenantKey).isEqualTo(tenant.id)
        assertThat(found.name).isEqualTo("Brand Theme")
        assertThat(found.description).isEqualTo("Corporate look")
    }

    @Test
    fun `GetTheme returns null for an unknown theme`(): Unit = withMediator {
        val tenant = createTenant("Theme Get Missing")
        val themeId = ThemeId(ThemeKey.of("does-not-exist"), CatalogId.default(TenantId(tenant.id)))

        assertThat(GetTheme(id = themeId).query()).isNull()
    }

    @Test
    fun `GetTheme does not leak themes across tenants`(): Unit = withMediator {
        val owner = createTenant("Theme Get Owner")
        val other = createTenant("Theme Get Other")
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId.default(TenantId(owner.id))), name = "Owner Theme").execute()

        val crossTenant = GetTheme(id = ThemeId(ThemeKey.of("brand"), CatalogId.default(TenantId(other.id)))).query()

        assertThat(crossTenant).isNull()
    }

    @Test
    fun `ListThemes returns the tenant's themes`(): Unit = withMediator {
        val tenant = createTenant("Theme List")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("minimal"), catalogId), name = "Minimal Theme").execute()

        val themes = ListThemes(tenantId = tenantId).query()

        // The list also contains the bundled system-catalog theme(s) every tenant gets.
        assertThat(themes.map { it.name }).contains("Brand Theme", "Minimal Theme")
    }

    @Test
    fun `ListThemes filters by search term`(): Unit = withMediator {
        val tenant = createTenant("Theme List Search")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()
        CreateTheme(id = ThemeId(ThemeKey.of("minimal"), catalogId), name = "Minimal Theme").execute()

        val matches = ListThemes(tenantId = tenantId, searchTerm = "brand").query()

        assertThat(matches).extracting<String> { it.name }.containsExactly("Brand Theme")
    }

    @Test
    fun `ListThemes filters by catalog`(): Unit = withMediator {
        val tenant = createTenant("Theme List Catalog")
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand Theme").execute()

        val defaultCatalogThemes = ListThemes(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query()

        // Only the authored theme lives in the default catalog; the system catalog's themes are excluded.
        assertThat(defaultCatalogThemes).extracting<String> { it.name }.containsExactly("Brand Theme")
        assertThat(defaultCatalogThemes).allMatch { it.catalogKey == CatalogKey.DEFAULT }
    }
}
