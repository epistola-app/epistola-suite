// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogId
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
import app.epistola.suite.generation.PrepareDraftRender
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.stencils.texts
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.ResolveTemplateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory

/**
 * A draft renders other catalogs as its declarations say (contract ADR 0003), so what a draft
 * previews is what its release is cut with: a pinned release from that release, a named draft from
 * that draft, whatever the other catalog's next version holds by now.
 *
 * `shared` released 1.0.0 with a black brand theme and a footer saying "Acme"; its next version has
 * since made the theme red and the footer "Acme Corp". `letters`' invoice uses both.
 */
class DraftRendersWithDeclarationsTest : IntegrationTestBase() {

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    private fun setUp(name: String): TenantKey = withMediator {
        val tenant = createTenant(name).id
        val catalog = CatalogId(shared, TenantId(tenant))
        CreateCatalog(tenant, shared, "Shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", documentStyles = mapOf("color" to "#000000")).execute()
        CreateStencil(StencilId(StencilKey.of("footer"), catalog), "Footer", content = footer("Acme")).execute()
        MarkCatalogReady(tenant, shared).execute()
        ReleaseCatalogVersion(tenant, shared, "1.0.0").execute()

        CreateCatalog(tenant, letters, "Letters").execute()
        CreateDocumentTemplate(invoice(tenant), "Invoice").execute().withRequiredDataExample()
        UpdateDocumentTemplate(invoice(tenant), themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
        UpdateVariantContent(
            VariantId(VariantKey.INITIAL, invoice(tenant)),
            stencilDocument {
                stencil("footer-1", "footer", catalogKey = "shared")
                build("footer-1")
            },
        ).execute()
        declareDependency(tenant, letters, on = shared, release = "1.0.0")

        UpdateTheme(ThemeId(ThemeKey.of("brand"), catalog), documentStyles = mapOf("color" to "#ff0000")).execute()
        UpdateStencil(StencilId(StencilKey.of("footer"), catalog), content = footer("Acme Corp")).execute()
        tenant
    }

    private fun invoice(tenant: TenantKey) = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant)))

    private fun footer(line: String) = stencilDocument {
        text("line", line)
        build("line")
    }

    private fun editorTheme(tenant: TenantKey): Any? = withMediator {
        ResolveTemplateTheme(tenant, ThemeKey.of("brand"), null, editorModel(tenant), templateCatalogKey = shared, catalogKey = letters).query()
    }?.documentStyles?.get("color")

    private fun editorModel(tenant: TenantKey) = withMediator { GetEditorContext(VariantId(VariantKey.INITIAL, invoice(tenant))).query() }!!.templateModel

    private fun prepared(tenant: TenantKey) = withMediator {
        PrepareDraftRender(
            tenant,
            letters,
            TemplateKey.of("invoice"),
            VariantKey.INITIAL,
            JsonNodeFactory.instance.objectNode().put("name", "x"),
            CatalogVersionRef(tenant, letters, DraftRef.Next),
        ).query()
    }

    @Test
    fun `a pinned release renders from that release, not from the other catalog's next version`() {
        val tenant = setUp("draft-pinned")

        assertThat(editorModel(tenant).texts()).`as`("the editor's stencil").containsExactly("Acme")
        assertThat(editorTheme(tenant)).`as`("the editor's theme").isEqualTo("#000000")
        val render = prepared(tenant)
        assertThat(render.model.texts()).`as`("a draft render's stencil").containsExactly("Acme")
        assertThat(render.theme.documentStyles?.get("color")).`as`("a draft render's theme").isEqualTo("#000000")
    }

    @Test
    fun `a named draft renders from that draft, live`() {
        val tenant = setUp("draft-named")
        val sharedNext = withMediator { app.epistola.suite.catalog.versions.GetCatalogDraft(tenant, shared).query() }!!.versionId
        val target = withMediator { GetCatalog(tenant, shared).query() }!!
        withMediator { UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", versionId = sharedNext))).execute() }

        assertThat(editorModel(tenant).texts()).containsExactly("Acme Corp")
        assertThat(editorTheme(tenant)).isEqualTo("#ff0000")
        assertThat(prepared(tenant).model.texts()).containsExactly("Acme Corp")
    }

    @Test
    fun `a named draft other than the next version renders from that draft`() {
        val tenant = setUp("draft-named-other")
        val redesign = withMediator { CreateCatalogDraft(tenant, shared, "Redesign", from = "1.0.0").execute() }.versionId
        withMediator {
            UpdateStencil(
                StencilId(StencilKey.of("footer"), CatalogId(shared, TenantId(tenant))),
                content = footer("Acme Redesign"),
                catalogVersion = CatalogVersionRef(tenant, shared, DraftRef.Id(redesign)),
            ).execute()
        }
        val target = withMediator { GetCatalog(tenant, shared).query() }!!
        withMediator { UpdateCatalogDependencies(tenant, letters, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", versionId = redesign))).execute() }

        assertThat(editorModel(tenant).texts()).containsExactly("Acme Redesign")
    }
}
