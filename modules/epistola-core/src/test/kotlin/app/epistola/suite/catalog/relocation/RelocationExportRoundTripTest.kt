// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceResolution
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A move is local to a tenant, but a catalog leaves it through export, which carries each
 * template's latest published version. A move leaves published versions naming the old address,
 * so an author adopts it by republishing; from then on the export names the new address and a
 * tenant that never saw the move can install the catalogs and resolve everything.
 *
 * Each case publishes `letters/invoice` using a resource in `letters`, moves that resource to
 * `shared`, republishes the invoice against `shared`, exports both catalogs, and installs them into
 * a fresh tenant.
 */
class RelocationExportRoundTripTest : RelocationTestSupport() {

    @Test
    fun `a moved stencil is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.STENCIL, letters, "header"),
        setup = { tenant ->
            val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
        },
        model = { catalog -> templateEmbedding("header", catalog.value) },
    )

    @Test
    fun `a moved theme is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.THEME, letters, "brand"),
        setup = { tenant -> CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand").execute() },
        model = { catalog -> usesTheme("brand", catalog.value) },
    )

    @Test
    fun `a moved image is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.IMAGE, letters, "logo"),
        setup = { tenant -> uploadPng(tenant, letters, "logo", renderablePng()) },
        model = { catalog -> singleNodeModel(Node(id = "logo", type = "image", props = mapOf("assetId" to "logo", "catalogKey" to catalog.value))) },
    )

    @Test
    fun `a moved font is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.FONT, letters, "acme"),
        setup = { tenant -> importFont(tenant, letters, "acme") },
        model = { catalog -> singleNodeModel(Node(id = "title", type = "text", styles = mapOf("fontFamily" to mapOf("slug" to "acme", "catalogKey" to catalog.value)))) },
    )

    /**
     * A variant names an attribute by key; the move rewrote `letters.brand` to `shared.brand`. The
     * exported variant has to carry that, and the fresh tenant has to accept the variant.
     */
    @Test
    fun `a moved attribute is exported at its new address and installs elsewhere`() {
        val tenant = tenantWith("Export after attribute move")
        val variant = templateVariant(tenant, letters)
        // Export carries a template only once it has a published version.
        publishTemplate(tenant, letters, textModel()) {
            CreateAttributeDefinition(AttributeId(AttributeKey.of("brand"), catalogId(tenant, letters)), "Brand", listOf("acme")).execute()
        }
        withMediator { UpdateVariant(variant, "Main", mapOf("letters.brand" to "acme")).execute() }
        move(tenant, address(CatalogResourceType.ATTRIBUTE, letters, "brand").movedTo(shared))

        val fresh = installBoth(tenant, "attribute")

        val installed = withMediator { ListVariants(templateVariant(fresh, letters).templateId).query() }.single { it.id == variant.key }
        assertThat(installed.attributes).containsExactlyEntriesOf(mapOf("shared.brand" to "acme"))
        withMediator { UpdateVariant(templateVariant(fresh, letters), "Main", installed.attributes).execute() }
    }

    @Test
    fun `an attribute bound to a moved code list installs elsewhere still bound to it`() {
        val tenant = tenantWith("Export after code list move")
        withMediator {
            CreateCodeList(
                CodeListId(CodeListKey.of("countries"), catalogId(tenant, letters)),
                displayName = "Countries",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("nl", "Nederland")),
            ).execute()
            CreateAttributeDefinition(
                AttributeId(AttributeKey.of("country"), catalogId(tenant, letters)),
                "Country",
                codeListId = CodeListId(CodeListKey.of("countries"), catalogId(tenant, letters)),
            ).execute()
        }
        move(tenant, address(CatalogResourceType.CODE_LIST, letters, "countries").movedTo(shared))

        val fresh = installBoth(tenant, "code list")

        val attribute = withMediator { GetAttributeDefinition(AttributeId(AttributeKey.of("country"), catalogId(fresh, letters))).query()!! }
        assertThat(attribute.codeListId).isEqualTo(CodeListId(CodeListKey.of("countries"), catalogId(fresh, shared)))
    }

    @Test
    fun `a moved template is exported from its new catalog only, and installs there`() {
        val tenant = tenantWith("Export after template move")
        publishTemplate(tenant, letters, textModel())
        move(tenant, address(CatalogResourceType.TEMPLATE, letters, "invoice").movedTo(shared))

        assertThat(unzipText(withMediator { ExportCatalogZip(tenant, letters).execute() }.zipBytes)).doesNotContainKey("resources/template/invoice.json")
        val fresh = installBoth(tenant, "template")

        assertThat(withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalogId(fresh, shared))).query() }).isNotNull()
    }

    /** Exports `shared` and `letters` from [tenant] and installs them, in that order, in a fresh tenant. */
    private fun installBoth(tenant: TenantKey, what: String): TenantKey {
        val sharedZip = withMediator { ExportCatalogZip(tenant, shared).execute() }.zipBytes
        val lettersZip = withMediator { ExportCatalogZip(tenant, letters).execute() }.zipBytes
        val fresh = createTenant("Installs $what move").id
        withMediator {
            ImportCatalogZip(fresh, sharedZip, CatalogType.AUTHORED).execute()
            ImportCatalogZip(fresh, lettersZip, CatalogType.AUTHORED).execute()
        }
        return fresh
    }

    /**
     * Runs [setup], publishes `letters/invoice` from [model] naming `letters`, moves [moved] to
     * `shared`, republishes the invoice from [model] naming `shared`, then exports both catalogs and
     * installs them in dependency order into a fresh tenant. The invoice exported from `letters` must
     * name the moved resource at `shared` and declare that dependency, and in the fresh tenant its
     * reference must resolve.
     */
    private fun roundTrip(moved: ResourceAddress, setup: (TenantKey) -> Unit, model: (CatalogKey) -> TemplateDocument) {
        val tenant = tenantWith("Export after ${moved.type.wireName} move")
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters)))
        withMediator {
            setup(tenant)
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variant, model(letters)).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
        }
        move(tenant, moved.movedTo(shared))
        withMediator {
            CreateVersion(variant).execute()
            UpdateDraft(variant, model(shared)).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
        }

        val sharedZip = withMediator { ExportCatalogZip(tenant, shared).execute() }.zipBytes
        val lettersZip = withMediator { ExportCatalogZip(tenant, letters).execute() }.zipBytes
        val lettersEntries = unzipText(lettersZip)
        assertThat(lettersEntries.getValue("resources/template/invoice.json"))
            .describedAs("the invoice names the %s where it lives now", moved.type.wireName)
            .contains("\"catalogKey\":\"shared\"")
        assertThat(lettersEntries.getValue("catalog.json")).describedAs("letters declares its dependency on shared").contains("\"shared\"")

        val fresh = createTenant("Installs ${moved.type.wireName} move").id
        withMediator {
            ImportCatalogZip(fresh, sharedZip, CatalogType.AUTHORED).execute()
            ImportCatalogZip(fresh, lettersZip, CatalogType.AUTHORED).execute()
        }
        val edge = withMediator { GetTenantResourceGraph(fresh, includeHistory = true).query() }.edges
            .single { it.source == address(CatalogResourceType.TEMPLATE, letters, "invoice") && it.targetSelector.type == moved.type }
        assertThat(edge.resolution).isEqualTo(ReferenceResolution.RESOLVED)
        assertThat(edge.target).isEqualTo(moved.copy(catalogKey = shared.value))
    }
}
