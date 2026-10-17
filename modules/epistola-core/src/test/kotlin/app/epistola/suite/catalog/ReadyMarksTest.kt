// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.MarkStencilReady
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.stencilDocument
import app.epistola.suite.stencils.texts
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.MarkTemplateReady
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * Ready marks (contract ADR 0002): a release contains only content someone marked ready, a mark holds
 * while the content is what was marked, and marking runs the checks publishing used to.
 */
class ReadyMarksTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String): CatalogId = withMediator {
        val tenant = createTenant(name).id
        CreateCatalog(tenant, letters, "Letters").execute()
        CatalogId(letters, TenantId(tenant))
    }

    private fun invoice(catalog: CatalogId): VariantId = withMediator {
        val templateId = TemplateId(TemplateKey.of("invoice"), catalog)
        CreateDocumentTemplate(templateId, "Invoice").execute().withRequiredDataExample()
        VariantId(VariantKey.INITIAL, templateId)
    }

    private fun changes(catalog: CatalogId) = withMediator { GetCatalogResourceChanges(catalog.tenantKey, catalog.key).query() }

    private fun readiness(catalog: CatalogId, type: String, key: String) = changes(catalog).resources.single { it.type == type && it.slug == key }

    private fun release(catalog: CatalogId, version: String) = withMediator { ReleaseCatalogVersion(catalog.tenantKey, catalog.key, version).execute() }

    // ── Whether a mark holds ──

    @Test
    fun `a new template is not ready until a variant is marked, and an edit makes the mark stale`() {
        val catalog = catalog("ready-edit")
        val variant = invoice(catalog)
        assertThat(readiness(catalog, "template", "invoice").let { it.state to it.ready }).isEqualTo(CatalogResourceState.NEW to false)

        withMediator { MarkVariantReady(variant).execute() }
        assertThat(readiness(catalog, "template", "invoice").ready).isTrue()

        withMediator { UpdateVariantContent(variant, document("edited")).execute() }
        assertThat(readiness(catalog, "template", "invoice").ready).isFalse()
    }

    @Test
    fun `a variant's readiness covers its template's data contract and which variants exist`() {
        val catalog = catalog("ready-contract")
        val variant = invoice(catalog)
        withMediator { MarkTemplateReady(variant.templateId).execute() }

        withMediator { UpdateDataContract(variant.templateId, dataModel = ObjectMapper().readValue("""{"type":"object"}""", tools.jackson.databind.node.ObjectNode::class.java)).execute() }
        assertThat(readiness(catalog, "template", "invoice").ready).`as`("contract edited").isFalse()

        withMediator { MarkTemplateReady(variant.templateId).execute() }
        withMediator { CreateVariant(VariantId(VariantKey.of("english"), variant.templateId), title = "English", description = null, attributes = emptyMap()).execute() }
        withMediator { MarkVariantReady(VariantId(VariantKey.of("english"), variant.templateId)).execute() }
        assertThat(readiness(catalog, "template", "invoice").ready).`as`("the default variant's mark predates the new variant").isFalse()
    }

    @Test
    fun `a theme is ready once the catalog is marked ready`() {
        val catalog = catalog("ready-theme")
        val theme = ThemeId(ThemeKey.of("brand"), catalog)
        withMediator { CreateTheme(theme, "Brand").execute() }
        assertThat(readiness(catalog, "theme", "brand").ready).isFalse()

        val remaining = withMediator { MarkCatalogReady(catalog.tenantKey, catalog.key).execute() }

        assertThat(remaining.notReady).isEmpty()
        withMediator { UpdateTheme(theme, documentStyles = mapOf("color" to "#ff0000")).execute() }
        assertThat(readiness(catalog, "theme", "brand").ready).isFalse()
    }

    // ── Releasing ──

    @Test
    fun `a release is refused while a changed resource is not ready, and goes through once it is`() {
        val catalog = catalog("ready-release")
        val variant = invoice(catalog)

        assertThatThrownBy { release(catalog, "1.0.0") }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.CATALOG_RESOURCES_NOT_READY)
            .hasMessageContaining("template 'invoice' (new)")

        withMediator { MarkVariantReady(variant).execute() }
        assertThat(release(catalog, "1.0.0").version).isEqualTo("1.0.0")

        // Released and unchanged: nothing waits on a mark.
        withMediator { UpdateVariantContent(variant, withMediator { GetVariantContent(variant).query()!! }).execute() }
        assertThat(changes(catalog).notReady).isEmpty()

        withMediator { UpdateVariantContent(variant, document("second")).execute() }
        assertThatThrownBy { release(catalog, "1.0.1") }
            .hasMessageContaining("template 'invoice' (modified)")
    }

    // ── What marking checks (formerly publishing) ──

    @Nested
    inner class StencilInstances {
        private val letterhead = stencilDocument {
            text("logo", "LOGO")
            placeholder("body", "body")
            build("logo", "body")
        }

        private fun filling(stencil: String, placeholder: String) = stencilDocument {
            text("my-body", "Dear customer")
            placeholder("fill", placeholder, fill = listOf("my-body"))
            stencil("inst", stencil, listOf("fill"))
            build("inst")
        }

        private fun setUp(name: String, parameterSchema: String? = null): Pair<CatalogId, VariantId> {
            val catalog = catalog(name)
            val stencil = StencilId(StencilKey.of("letterhead"), catalog)
            withMediator {
                CreateStencil(stencil, "Letterhead", content = letterhead).execute()
                parameterSchema?.let { UpdateStencil(stencil, parameterSchema = ObjectMapper().readTree(it)).execute() }
            }
            return catalog to invoice(catalog)
        }

        private fun markWith(variant: VariantId, model: TemplateDocument) = withMediator {
            UpdateVariantContent(variant, model).execute()
            MarkVariantReady(variant).execute()
        }

        @Test
        fun `an instance that fits its stencil can be marked ready`() {
            val (catalog, variant) = setUp("fit-ok")

            markWith(variant, filling("letterhead", "body"))

            assertThat(readiness(catalog, "template", "invoice").ready).isTrue()
        }

        @Test
        fun `an instance of a stencil that does not exist is refused`() {
            val (_, variant) = setUp("fit-missing")

            assertThatThrownBy { markWith(variant, filling("no-such-stencil", "body")) }
                .isInstanceOf(ValidationException::class.java)
                .hasValidationCode(ValidationCode.STENCIL_REFERENCE_INVALID)
                .hasMessageContaining("no-such-stencil, which does not exist")
        }

        @Test
        fun `a fill for a placeholder the stencil does not have is refused`() {
            val (_, variant) = setUp("fit-placeholder")

            assertThatThrownBy { markWith(variant, filling("letterhead", "footer")) }
                .hasValidationCode(ValidationCode.STENCIL_REFERENCE_INVALID)
                .hasMessageContaining("placeholder 'footer'")
        }

        @Test
        fun `an unbound required parameter may be saved, but not marked ready`() {
            val (_, variant) = setUp("fit-required", """{"type":"object","properties":{"recipientName":{"type":"string"}},"required":["recipientName"]}""")

            withMediator { UpdateVariantContent(variant, filling("letterhead", "body")).execute() }

            assertThatThrownBy { withMediator { MarkVariantReady(variant).execute() } }
                .hasValidationCode(ValidationCode.NODE_PARAMETER_BINDING_MISSING_REQUIRED)
                .hasMessageContaining("recipientName")
        }

        @Test
        fun `a stencil whose own instance does not fit cannot be marked ready`() {
            val (catalog, _) = setUp("fit-stencil")
            val frame = StencilId(StencilKey.of("frame"), catalog)
            withMediator { CreateStencil(frame, "Frame", content = filling("letterhead", "footer")).execute() }

            assertThatThrownBy { withMediator { MarkStencilReady(frame).execute() } }
                .hasMessageContaining("placeholder 'footer'")
        }

        @Test
        fun `a model saved with the stencil inlined is stored with only the reference and the fills`() {
            val (_, variant) = setUp("fit-collapse")
            val inlined = app.epistola.suite.stencils.model.StencilInstances.expand(filling("letterhead", "body"), letters.value) { _, _ ->
                app.epistola.suite.stencils.model.StencilInstances.Content(letterhead, null)
            }.document
            assertThat(inlined.texts()).contains("LOGO", "Dear customer")

            withMediator { UpdateVariantContent(variant, inlined).execute() }

            assertThat(withMediator { GetVariantContent(variant).query()!! }.texts()).containsExactly("Dear customer")
        }
    }

    @Test
    fun `nothing in a subscribed catalog can be marked ready`() {
        val tenant: TenantKey = createTenant("ready-subscribed").id
        withMediator { RegisterCatalog(tenantKey = tenant, sourceUrl = "classpath:epistola/catalogs/fixture/catalog.json", authType = AuthType.NONE).execute() }
        val variant = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of("hello-world"), CatalogId(CatalogKey.of("epistola-demo"), TenantId(tenant))))

        assertThatThrownBy { withMediator { MarkVariantReady(variant).execute() } }
            .isInstanceOf(CatalogReadOnlyException::class.java)
    }

    /** A document holding one text, so two calls with different [text]s differ. */
    private fun document(text: String): TemplateDocument = stencilDocument {
        text("t", text)
        build("t")
    }
}
