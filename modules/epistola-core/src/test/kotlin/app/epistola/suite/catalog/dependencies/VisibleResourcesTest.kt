// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.ListVisibleAttributeDefinitions
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.systemCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.fonts.queries.ListVisibleFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withoutSystemCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.ListVisibleThemes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a picker offers is what the catalog version can use (contract ADR 0003, [VisibleVersions]):
 * its own catalog's resources, then those of each catalog it declares, at the release or draft the
 * declaration names. Never a catalog it does not declare, nor what a declared catalog declares in turn.
 */
class VisibleResourcesTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val other = CatalogKey.of("other")
    private val base = CatalogKey.of("base")

    private fun systemRelease(tenant: TenantKey): String = withMediator { GetCatalog(systemCatalog(tenant)).query()!!.latestReleaseVersion!! }

    /** [catalog] declares the system catalog as it does, then each of [plus]. */
    private fun declare(tenant: TenantKey, catalog: CatalogKey, vararg plus: DeclaredCatalogDependency) = withMediator {
        val system = ListCatalogDependencies(testCatalog(tenant, catalog), catalogVersion = null).query()
            .filter { it.catalogKey == SYSTEM_CATALOG_KEY.value }
            .map { DeclaredCatalogDependency(it.registry, it.namespace, it.catalogKey, it.releaseVersion) }
        UpdateCatalogDependencies(testCatalog(tenant, catalog), system + plus, catalogVersion = null).execute()
    }

    private fun on(tenant: TenantKey, key: CatalogKey, releaseVersion: String? = null, draft: String? = null): DeclaredCatalogDependency {
        val catalog = withMediator { GetCatalog(testCatalog(tenant, key)).query()!! }
        return DeclaredCatalogDependency(catalog.registry, catalog.namespace, key.value, releaseVersion, draft)
    }

    private fun release(tenant: TenantKey, key: CatalogKey, version: String) = withMediator {
        MarkCatalogReady(testCatalog(tenant, key), catalogVersion = null).execute()
        ReleaseCatalogDraft(testCatalog(tenant, key), version, catalogVersion = null).execute()
    }

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String, name: String, version: CatalogReleaseRef? = null) = withMediator {
        CreateTheme(ThemeId(ThemeKey.of(key), testCatalog(tenant, catalog)), name, catalogVersion = version).execute()
    }

    @Test
    fun `themes come from the catalog itself, then each declared catalog at the release or draft it names`() {
        val tenant = createTenant("visible-themes").id
        withMediator { listOf(letters, shared, other).forEach { CreateCatalog(tenant, it, it.value).execute() } }
        theme(tenant, letters, "house", "Letters house")
        theme(tenant, shared, "brand", "Shared brand")
        release(tenant, shared, "1.0.0")
        // Added after the release the declaration pins: not what that release renders with.
        theme(tenant, shared, "later", "Shared later")
        val draft = withMediator { CreateCatalogDraft(testCatalog(tenant, other), "Redesign").execute().versionId }
        theme(tenant, other, "redesign", "Other redesign", CatalogReleaseRef(testCatalog(tenant, other), DraftRef.Id(draft)))
        declare(tenant, letters, on(tenant, shared, releaseVersion = "1.0.0"), on(tenant, other, draft = draft))

        val themes = withMediator { ListVisibleThemes(testCatalog(tenant, letters), catalogVersion = null).query() }

        assertThat(themes.map { it.catalogKey.value }.distinct()).containsExactly(letters.value, SYSTEM_CATALOG_KEY.value, shared.value, other.value)
        assertThat(themes.first().name).isEqualTo("Letters house")
        assertThat(themes.map { it.name }).contains("Shared brand", "Other redesign").doesNotContain("Shared later")
    }

    @Test
    fun `a catalog it does not declare is not visible, nor what a declared catalog declares in turn`() {
        val tenant = createTenant("visible-not-transitive").id
        withMediator { listOf(letters, shared, base, other).forEach { CreateCatalog(tenant, it, it.value).execute() } }
        theme(tenant, base, "base", "Base theme")
        release(tenant, base, "1.0.0")
        theme(tenant, other, "other", "Other theme")
        theme(tenant, shared, "brand", "Shared brand")
        declare(tenant, shared, on(tenant, base, releaseVersion = "1.0.0"))
        release(tenant, shared, "1.0.0")
        declare(tenant, letters, on(tenant, shared, releaseVersion = "1.0.0"))

        val names = withMediator { ListVisibleThemes(testCatalog(tenant, letters), catalogVersion = null).query() }.map { it.name }

        assertThat(names).contains("Shared brand").doesNotContain("Base theme", "Other theme")
    }

    @Test
    fun `attributes are the system locale and a declared catalog's, not an undeclared one's`() {
        val tenant = createTenant("visible-attributes").id
        withMediator {
            listOf(letters, shared).forEach { CreateCatalog(tenant, it, it.value).execute() }
            CreateAttributeDefinition(AttributeId(AttributeKey.of("audience"), testCatalog(tenant, shared)), "Audience", catalogVersion = null).execute()
        }
        release(tenant, shared, "1.0.0")
        fun visible() = withMediator { ListVisibleAttributeDefinitions(testCatalog(tenant, letters), catalogVersion = null).query() }.map { "${it.catalogKey.value}.${it.id.value}" }

        assertThat(visible()).contains("system.locale").doesNotContain("shared.audience")

        declare(tenant, letters, on(tenant, shared, releaseVersion = "1.0.0"))

        assertThat(visible()).contains("system.locale", "shared.audience")
    }

    @Test
    fun `fonts are the declared system release's, with their faces and that release to serve them from`() {
        val tenant = createTenant("visible-fonts").id
        val fonts = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            ListVisibleFonts(testCatalog(tenant, letters), catalogVersion = null).query()
        }

        val inter = fonts.single { it.font.slug.value == "inter" }
        assertThat(fonts.map { it.font.catalogKey }).containsOnly(SYSTEM_CATALOG_KEY)
        assertThat(inter.version).isEqualTo(CatalogReleaseRef(systemCatalog(tenant), DraftRef.Released(systemRelease(tenant))))
        assertThat(inter.faces.map { it.weight to it.italic }).contains(400 to false)
    }

    @Test
    fun `without the system catalog declared, no system font is visible`() {
        val tenant = createTenant("visible-no-fonts").id
        val fonts = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            withoutSystemCatalog(testCatalog(tenant, letters))
            ListVisibleFonts(testCatalog(tenant, letters), catalogVersion = null).query()
        }

        assertThat(fonts).isEmpty()
    }
}
