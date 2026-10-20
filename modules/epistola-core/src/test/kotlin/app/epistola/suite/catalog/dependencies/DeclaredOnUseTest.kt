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
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
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
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A write that starts using another catalog declares it ([UsedCatalogDeclarer]): an authored one by
 * naming its next version, a subscribed one by pinning its installed release. What an author
 * declared is left alone.
 */
class DeclaredOnUseTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** `shared` (brand theme, footer stencil, colours code list), released 1.0.0, and an empty `letters`. */
    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        val catalog = CatalogId(shared, TenantId(tenant))
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
        CreateStencil(StencilId(StencilKey.of("footer"), catalog), "Footer").execute()
        CreateCodeList(CodeListId(CodeListKey.of("colours"), catalog), "Colours", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("red", "Red"))).execute()
        MarkCatalogReady(tenant, shared).execute()
        ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()
        CreateCatalog(tenant, letters, "Letters").execute()
        tenant
    }

    private fun declared(tenant: TenantKey, catalog: CatalogKey = letters) = withMediator { ListCatalogDependencies(tenant, catalog).query() }
        .map { Triple(it.catalogKey, it.releaseVersion, it.draftName) }

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))).also {
        withMediator { runCatching { CreateDocumentTemplate(it, "Invoice").execute().withRequiredDataExample() } }
    }

    @Test
    fun `a stencil instance in a variant declares the stencil's catalog, naming its next version`() {
        val tenant = setUp("on-use-stencil")
        withMediator {
            UpdateVariantContent(
                VariantId(VariantKey.INITIAL, invoice(tenant)),
                stencilDocument {
                    stencil("footer-1", "footer", catalogKey = "shared")
                    build("footer-1")
                },
            ).execute()
        }

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next version"))
    }

    @Test
    fun `a template's theme declares the theme's catalog`() {
        val tenant = setUp("on-use-theme")
        withMediator { UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute() }

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next version"))
    }

    @Test
    fun `an attribute bound to another catalog's code list declares that catalog`() {
        val tenant = setUp("on-use-code-list")
        withMediator {
            CreateAttributeDefinition(
                AttributeId(AttributeKey.of("colour"), CatalogId(letters, TenantId(tenant))),
                "Colour",
                codeListId = CodeListId(CodeListKey.of("colours"), CatalogId(shared, TenantId(tenant))),
            ).execute()
        }

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next version"))
    }

    @Test
    fun `a theme using the system catalog's font pins the installed system release, as a subscribed catalog has no draft`() {
        val tenant = setUp("on-use-font")
        val theme = ThemeId(ThemeKey.of("house"), CatalogId(letters, TenantId(tenant)))
        withMediator {
            CreateTheme(theme, "House").execute()
            UpdateTheme(theme, documentStyles = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))).execute()
        }

        val system = declared(tenant).single()
        assertThat(system.first).isEqualTo(SYSTEM_CATALOG_KEY.value)
        assertThat(system.second).`as`("pinned").isNotNull()
        assertThat(system.third).isNull()
    }

    @Test
    fun `a catalog already declared keeps what the author declared`() {
        val tenant = setUp("on-use-kept")
        withMediator { declareDependency(tenant, letters, on = shared, release = "1.0.0") }

        withMediator { UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute() }

        assertThat(declared(tenant)).containsExactly(Triple("shared", "1.0.0", null))
    }

    @Test
    fun `using the latest versions moves pins and leaves a declaration naming a draft as it is`() {
        val tenant = setUp("on-use-upgrade")
        withMediator { UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute() }

        withMediator { UpgradeCatalogDependencies(tenant, letters).execute() }

        assertThat(declared(tenant)).containsExactly(Triple("shared", null, "Next version"))
    }

    @Test
    fun `two catalogs cannot come to use each other, the second use is refused as it is made`() {
        val tenant = setUp("on-use-cycle")
        withMediator {
            // Letters uses Shared's theme; then Shared would use a theme Letters has.
            UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), CatalogId(letters, TenantId(tenant))), "House").execute()
            val memo = TemplateId(TemplateKey.of("memo"), CatalogId(shared, TenantId(tenant)))
            CreateDocumentTemplate(memo, "Memo").execute().withRequiredDataExample()

            assertThatThrownBy { UpdateDocumentTemplate(memo, themeId = ThemeKey.of("house"), themeCatalogKey = letters).execute() }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("Catalog 'shared' cannot use 'letters'")
        }
    }
}
