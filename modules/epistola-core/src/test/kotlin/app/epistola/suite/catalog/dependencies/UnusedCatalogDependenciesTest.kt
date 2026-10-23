// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.RemoveUnusedCatalogDependencies
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A declaration of another catalog that nothing in the version uses (what a merge can leave behind)
 * is listed as unused and removed only when asked; one still used, in a resource or in the catalog's
 * settings, is neither.
 */
class UnusedCatalogDependenciesTest : IntegrationTestBase() {
    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    /** Shared released with a theme `base`; Letters declares Shared 1.0.0 and uses nothing of it. */
    private fun declaredUnused(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            CreateCatalog(tenantKey = tenant, id = shared, name = "Shared").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("base"), CatalogId(shared, TenantId(tenant))), name = "Base").execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogDraft(tenantKey = tenant, catalogKey = shared, version = "1.0.0").execute()
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            declareDependency(tenant, letters, shared, "1.0.0")
        }
        return tenant
    }

    private fun unused(tenant: TenantKey) = withMediator { GetCatalogBorrowing(tenant, letters).query().unused.map { it.alias } }

    @Test
    fun `a declaration nothing uses is listed as unused, and removing it leaves the others`() {
        val tenant = declaredUnused("unused-removed")
        assertThat(unused(tenant)).containsExactly("shared")

        val result = withMediator { RemoveUnusedCatalogDependencies(tenant, letters, listOf("shared")).execute() }

        assertThat(result.removed).containsExactly("shared")
        assertThat(withMediator { GetCatalogBorrowing(tenant, letters).query().catalogs.filter { it.declared } }).isEmpty()
    }

    @Test
    fun `a catalog whose theme is the default theme is used, and its declaration is not removed`() {
        val tenant = declaredUnused("unused-default-theme")
        withMediator { SetCatalogDefaultTheme(tenant, letters, CatalogDefaultTheme(ThemeKey.of("base"), shared)).execute() }

        assertThat(unused(tenant)).isEmpty()
        assertThatThrownBy { withMediator { RemoveUnusedCatalogDependencies(tenant, letters, listOf("shared")).execute() } }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("shared is used")
        assertThat(withMediator { GetCatalogBorrowing(tenant, letters).query().catalogs.single { it.alias == "shared" }.declared }).isTrue()
    }

    @Test
    fun `a catalog whose attribute a variant carries is used`() {
        val tenant = declaredUnused("unused-attribute")
        withMediator {
            val template = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))
            CreateDocumentTemplate(template, "Invoice").execute()
            CreateVariant(VariantId(VariantKey.of("dutch"), template), "Dutch", null, attributes = mapOf("system.locale" to "nl-NL")).execute()
        }

        assertThat(unused(tenant)).doesNotContain("system")
        assertThat(withMediator { GetCatalogBorrowing(tenant, letters).query().catalogs.single { it.catalogKey == "system" }.used }).isTrue()
    }
}
