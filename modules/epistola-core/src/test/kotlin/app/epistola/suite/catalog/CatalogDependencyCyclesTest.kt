// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Catalogs never use each other. Every write that starts using another catalog declares it, and a
 * declaration naming a catalog that already uses this one, directly or through others, is refused
 * with the write.
 */
class CatalogDependencyCyclesTest : IntegrationTestBase() {

    private fun catalogs(name: String, vararg keys: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        keys.forEach { key ->
            val catalog = CatalogKey.of(key)
            CreateCatalog(tenant, catalog, key).execute()
            CreateTheme(ThemeId(ThemeKey.of("theme"), CatalogId(catalog, TenantId(tenant))), "Theme of $key").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("letter"), CatalogId(catalog, TenantId(tenant))), "Letter").execute().withRequiredDataExample()
        }
        tenant
    }

    /** `user`'s letter takes `used`'s theme, which declares `used` on `user`'s draft [at]. */
    private fun useTheme(tenant: TenantKey, user: String, used: String, at: DraftRef = DraftRef.Next) = withMediator {
        UpdateDocumentTemplate(
            TemplateId(TemplateKey.of("letter"), CatalogId(CatalogKey.of(user), TenantId(tenant))),
            themeId = ThemeKey.of("theme"),
            themeCatalogKey = CatalogKey.of(used),
            catalogVersion = CatalogReleaseRef(tenant, CatalogKey.of(user), at),
        ).execute()
    }

    private fun themeOfLetter(tenant: TenantKey, catalog: String) = withMediator {
        GetDocumentTemplate(TemplateId(TemplateKey.of("letter"), CatalogId(CatalogKey.of(catalog), TenantId(tenant)))).query()?.themeCatalogKey?.value
    }

    private fun declared(tenant: TenantKey, catalog: String) = withMediator { ListCatalogDependencies(tenant, CatalogKey.of(catalog)).query().map { it.catalogKey } }

    @Test
    fun `using a catalog that already uses this one is refused, and nothing of the write remains`() {
        val tenant = catalogs("Cycle Direct", "letters", "shared")
        useTheme(tenant, "letters", "shared")

        assertThatThrownBy { useTheme(tenant, "shared", "letters") }
            .isInstanceOf(ValidationException::class.java)
            .hasFieldOrPropertyWithValue("code", ValidationCode.CATALOG_DEPENDENCY_INVALID)
            .hasMessageContaining("Catalog 'shared' cannot use 'letters': 'letters' already uses 'shared'")
            .hasMessageContaining("catalogs never use each other")

        assertThat(themeOfLetter(tenant, "shared")).`as`("the template change is rolled back with it").isNull()
        assertThat(declared(tenant, "shared")).doesNotContain("letters")
        assertThat(declared(tenant, "letters")).containsExactly("shared")
    }

    @Test
    fun `a loop through other catalogs is refused too, naming the way round`() {
        val tenant = catalogs("Cycle Indirect", "letters", "shared", "base")
        useTheme(tenant, "letters", "shared")
        useTheme(tenant, "shared", "base")

        assertThatThrownBy { useTheme(tenant, "base", "letters") }
            .hasMessageContaining("Catalog 'base' cannot use 'letters'")
            .hasMessageContaining("('letters' uses 'shared' uses 'base')")
        // One way round is fine.
        assertThat(declared(tenant, "base")).isEmpty()
    }

    @Test
    fun `a parallel draft cannot close the loop either`() {
        val tenant = catalogs("Cycle Parallel", "letters", "shared")
        withMediator {
            MarkCatalogReady(tenant, CatalogKey.of("shared")).execute()
            ReleaseCatalogDraft(tenant, CatalogKey.of("shared"), "1.0.0").execute()
        }
        val redesign = withMediator { requireNotNull(CreateCatalogDraft(tenant, CatalogKey.of("shared"), "Redesign").execute().versionId) }
        // shared's parallel draft starts using letters...
        useTheme(tenant, "shared", "letters", DraftRef.Id(redesign))

        // ...so letters can no longer use shared, whichever of shared's drafts it would come from.
        assertThatThrownBy { useTheme(tenant, "letters", "shared") }
            .hasMessageContaining("Catalog 'letters' cannot use 'shared': 'shared' already uses 'letters'")
    }
}
