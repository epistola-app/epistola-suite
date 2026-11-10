// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclareCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpgradeCatalogDependencies
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
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
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.testing.withoutSystemCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Declaring one catalog ([DeclareCatalogDependency]), the step before using its resources: an authored
 * one is named at its next version, and what an author declared already is left alone. Two catalogs
 * never come to use each other: declaring the second way round is refused.
 */
class DeclareCatalogDependencyTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` (brand theme, footer stencil, colours code list), released 1.0.0, and an empty `letters`. */
    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        val catalog = testCatalog(TenantId(tenant), shared)
        CreateCatalog(tenant, shared, "Shared").execute()
        withoutSystemCatalog(testCatalog(tenant, shared))
        CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", catalogVersion = null).execute()
        CreateStencil(StencilId(StencilKey.of("footer"), catalog), "Footer", catalogVersion = null).execute()
        CreateCodeList(CodeListId(CodeListKey.of("colours"), catalog), "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red")), catalogVersion = null).execute()
        MarkCatalogReady(testCatalog(tenant, shared), catalogVersion = null).execute()
        ReleaseCatalogDraft(testCatalog(tenant, shared), "1.0.0", catalogVersion = null).execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        withoutSystemCatalog(testCatalog(tenant, letters))
        tenant
    }

    private fun declared(tenant: TenantKey, catalog: CatalogKey = letters) = withMediator { ListCatalogDependencies(testCatalog(tenant, catalog), catalogVersion = null).query() }
        .map { Triple(it.catalogKey, it.releaseVersion, it.draftName) }

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters)).also {
        withMediator { runCatching { CreateDocumentTemplate(it, "Invoice", catalogVersion = null).execute().withRequiredDataExample() } }
    }

    private fun declare(tenant: TenantKey, catalog: CatalogKey, on: CatalogKey) = withMediator {
        DeclareCatalogDependency(testCatalog(tenant, catalog), testCatalog(tenant, on), catalogVersion = null).execute()
    }

    @Test
    fun `an authored catalog is declared by naming its next version, not its release`() {
        val tenant = setUp("declare-next")

        declare(tenant, letters, on = shared)

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next release"))
    }

    @Test
    fun `a catalog already declared keeps what the author declared`() {
        val tenant = setUp("declare-kept")
        withMediator { declareDependency(tenant, letters, on = shared, release = "1.0.0") }

        assertThat(declare(tenant, letters, on = shared)).isEmpty()
        withMediator { UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice(tenant).tenantKey, shared), catalogVersion = null).execute() }

        assertThat(declared(tenant)).containsExactly(Triple("shared", "1.0.0", null))
    }

    @Test
    fun `using the latest versions moves pins and leaves a declaration naming a draft as it is`() {
        val tenant = setUp("declare-upgrade")
        declare(tenant, letters, on = shared)
        withMediator { UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalog = testCatalog(invoice(tenant).tenantKey, shared), catalogVersion = null).execute() }

        withMediator { UpgradeCatalogDependencies(testCatalog(tenant, letters), catalogVersion = null).execute() }

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next release"))
    }

    @Test
    fun `two catalogs cannot come to use each other, declaring the second way round is refused`() {
        val tenant = setUp("declare-cycle")
        declare(tenant, letters, on = shared)

        assertThatThrownBy { declare(tenant, shared, on = letters) }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Catalog 'shared' cannot use 'letters'")
    }
}
