// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ReferenceResolution
import app.epistola.suite.catalog.graph.ResourceAddress
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
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A move is local to a tenant, but a catalog leaves it through export. An exported catalog must not
 * carry the tenant's aliases: the dependency a move created has to be written at its new address,
 * so a tenant that never saw the move can install the catalogs and resolve everything.
 *
 * Each case publishes `letters/invoice` using a resource in `letters`, moves that resource to
 * `shared`, exports both catalogs, and installs them into a fresh tenant.
 */
class RelocationExportRoundTripTest : RelocationTestSupport() {

    @Test
    fun `a moved stencil is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.STENCIL, letters, "header"),
        setup = { tenant ->
            val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
            templateEmbedding("header", letters.value)
        },
    )

    @Test
    fun `a moved theme is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.THEME, letters, "brand"),
        setup = { tenant ->
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand").execute()
            usesTheme("brand", letters.value)
        },
    )

    @Test
    fun `a moved image is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.IMAGE, letters, "logo"),
        setup = { tenant ->
            uploadPng(tenant, letters, "logo", renderablePng())
            singleNodeModel(Node(id = "logo", type = "image", props = mapOf("assetId" to "logo", "catalogKey" to letters.value)))
        },
    )

    @Test
    fun `a moved font is exported at its new address and installs elsewhere`() = roundTrip(
        moved = address(CatalogResourceType.FONT, letters, "acme"),
        setup = { tenant ->
            importFont(tenant, letters, "acme")
            singleNodeModel(Node(id = "title", type = "text", styles = mapOf("fontFamily" to mapOf("slug" to "acme", "catalogKey" to letters.value))))
        },
    )

    /**
     * Publishes `letters/invoice` from the model [setup] returns, moves [moved] to `shared`, then
     * exports both catalogs and installs them in dependency order into a fresh tenant. The invoice
     * exported from `letters` must name the moved resource at `shared` and declare that dependency,
     * and in the fresh tenant its reference must resolve.
     */
    private fun roundTrip(moved: ResourceAddress, setup: (TenantKey) -> TemplateDocument) {
        val tenant = tenantWith("Export after ${moved.type.wireName} move")
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters)))
        withMediator {
            val model = setup(tenant)
            CreateDocumentTemplate(variant.templateId, "Invoice").execute().withRequiredDataExample()
            UpdateDraft(variant, model).execute()
            PublishVersion(VersionId(GetDraft(variant).query()!!.id, variant)).execute()
        }
        move(tenant, moved.movedTo(shared))

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
