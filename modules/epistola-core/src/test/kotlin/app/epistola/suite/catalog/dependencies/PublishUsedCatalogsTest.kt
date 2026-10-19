// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.ResolveCatalogDependencies
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.queries.ListCatalogReleases
import app.epistola.suite.catalog.queries.ListReleaseDependencies
import app.epistola.suite.common.ids.CatalogId
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
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Publishing with what the preview showed (docs/catalog-working-model.md, rule 7): a catalog used
 * here whose work differs from what a publish would tie to is published along, or re-tied, in one
 * transaction.
 */
class PublishUsedCatalogsTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` with a brand theme published as 1.0.0; `letters` with an invoice using it, declared. */
    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(brand(tenant), "Brand", documentStyles = mapOf("color" to "#000000")).execute()
        MarkCatalogReady(tenant, shared).execute()
        ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        declareDependency(tenant, letters, on = shared)
        useTheme(tenant, "brand")
        tenant
    }

    private fun brand(tenant: TenantKey) = ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant)))

    private fun useTheme(tenant: TenantKey, theme: String) = withMediator {
        val invoice = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))
        runCatching { CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample() }
        UpdateDocumentTemplate(invoice, themeId = ThemeKey.of(theme), themeCatalogKey = shared).execute()
    }

    private fun sharedReleases(tenant: TenantKey) = withMediator { ListCatalogReleases(tenant, shared).query().map { it.version } }

    @Test
    fun `a used resource changed in the other catalog's work is published along, and this version is tied to it`() {
        val tenant = setUp("along-changed")
        withMediator { UpdateTheme(brand(tenant), documentStyles = mapOf("color" to "#ff0000")).execute() }

        val borrowed = withMediator { GetCatalogBorrowing(tenant, letters).query() }.catalogs.single()
        assertThat(borrowed.publishAlong).isTrue()
        assertThat(borrowed.unpublishedUsed.map { it.key }).containsExactly("brand")
        val number = borrowed.nextNumber!!

        val tied = withMediator { PublishUsedCatalogs(tenant, letters, publish = setOf("shared")).execute() }

        assertThat(tied).containsEntry("shared", number)
        assertThat(sharedReleases(tenant)).contains(number)
        withMediator {
            MarkCatalogReady(tenant, letters).execute()
            ReleaseCatalogVersion(tenant, letters, "1.0.0").execute()
        }
        assertThat(withMediator { ListReleaseDependencies(tenant, letters, "1.0.0").query() }.map { it.catalogKey to it.releaseVersion })
            .`as`("published as previewed").containsExactly("shared" to number)
    }

    @Test
    fun `a used resource that only exists in the other catalog's work makes publishing along necessary`() {
        val tenant = setUp("along-only-in-work")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("seasonal"), CatalogId(shared, TenantId(tenant))), "Seasonal").execute() }
        useTheme(tenant, "seasonal")

        val borrowed = withMediator { GetCatalogBorrowing(tenant, letters).query() }.catalogs.single()

        assertThat(borrowed.onlyInWork).isTrue()
        assertThat(borrowed.publishAlong).isTrue()
    }

    @Test
    fun `a newer published version than the one tied to is re-tied, without publishing anything`() {
        val tenant = setUp("along-retie")
        withMediator { ResolveCatalogDependencies(tenant, letters).execute() }
        withMediator {
            UpdateTheme(brand(tenant), documentStyles = mapOf("color" to "#00ff00")).execute()
            MarkCatalogReady(tenant, shared).execute()
            ReleaseCatalogVersion(tenant, shared, "1.0.1").execute()
        }

        val borrowed = withMediator { GetCatalogBorrowing(tenant, letters).query() }.catalogs.single()
        assertThat(borrowed.publishAlong).isFalse()
        assertThat(borrowed.retieTo).isEqualTo("1.0.1")

        withMediator { PublishUsedCatalogs(tenant, letters, publish = emptySet(), retie = setOf("shared")).execute() }

        assertThat(withMediator { ListCatalogDependencies(tenant, letters).query() }.single().releaseVersion).isEqualTo("1.0.1")
        assertThat(sharedReleases(tenant)).`as`("nothing published").containsExactly("1.0.1", "1.0.0")
    }
}
