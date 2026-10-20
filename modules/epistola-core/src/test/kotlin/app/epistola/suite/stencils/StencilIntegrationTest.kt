// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.DeleteStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * A stencil is one working copy that every template uses by reference (contract ADR 0006): editing
 * it changes what each template renders, and which templates still fit it is computed, not upgraded.
 */
class StencilIntegrationTest : IntegrationTestBase() {

    /** Wrapper that ensures withMediator returns Unit (JUnit requires void test methods). */
    private fun test(block: () -> Unit) = withMediator(block)

    private fun stencilId(tenantId: TenantId) = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

    /** A letterhead: a logo line, then a `body` placeholder. */
    private fun letterhead(logo: String = "ACME LOGO", placeholderName: String = "body") = stencilDocument {
        text("logo", logo)
        placeholder("body", placeholderName)
        build("logo", "body")
    }

    private fun templateFilling(stencil: StencilId, placeholderName: String = "body") = stencilDocument {
        text("my-body", "Dear customer")
        placeholder("fill", placeholderName, fill = listOf("my-body"))
        stencil("inst", stencil.key.value, listOf("fill"))
        build("inst")
    }

    private fun draftUsing(tenantId: TenantId, model: TemplateDocument): VariantId {
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter").execute().withRequiredDataExample()
        val variantId = VariantId(VariantKey.INITIAL, templateId)
        UpdateVariantContent(variantId = variantId, templateModel = model).execute()
        return variantId
    }

    private fun previewText(variantId: VariantId): String {
        val pdf = PreviewVariant(
            tenantId = variantId.tenantKey,
            catalogKey = variantId.templateId.catalogId.key,
            templateId = variantId.templateId.key,
            variantId = variantId.key,
            data = ObjectMapper().createObjectNode(),
        ).query()
        return PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { document ->
            (1..document.numberOfPages).joinToString("\n") { PdfTextExtractor.getTextFromPage(document.getPage(it)) }
        }
    }

    // ── CRUD ──

    @Test
    fun `create and retrieve stencil`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))

        val stencil = CreateStencil(
            id = id,
            name = "Corporate Header",
            description = "Standard header",
            tags = listOf("header", "corporate"),
        ).execute()

        assertThat(stencil.name).isEqualTo("Corporate Header")
        assertThat(stencil.description).isEqualTo("Standard header")
        assertThat(stencil.tags).containsExactly("header", "corporate")
        assertThat(GetStencil(id = id).query()!!.name).isEqualTo("Corporate Header")
    }

    @Test
    fun `create stencil with initial content stores it as the working copy`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))

        CreateStencil(id = id, name = "With Content", content = letterhead()).execute()

        val stored = GetStencil(id = id).query()!!
        assertThat(stored.content.texts()).containsExactly("ACME LOGO")
        assertThat(stored.parameterSchema).isNull()
        assertThat(stored.readsTemplateData).isFalse()
    }

    @Test
    fun `list stencils with search`() = test {
        val tenantId = TenantId(createTenant("Test Tenant").id)

        CreateStencil(id = stencilId(tenantId), name = "Corporate Header").execute()
        CreateStencil(id = stencilId(tenantId), name = "Invoice Footer").execute()

        assertThat(ListStencils(tenantId = tenantId).query()).hasSize(2)
        val searched = ListStencils(tenantId = tenantId, searchTerm = "Header").query()
        assertThat(searched.map { it.name }).containsExactly("Corporate Header")
    }

    @Test
    fun `update stencil metadata`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))
        CreateStencil(id = id, name = "Old Name").execute()

        val updated = UpdateStencil(id = id, name = "New Name", tags = listOf("updated")).execute()

        assertThat(updated!!.name).isEqualTo("New Name")
        assertThat(updated.tags).containsExactly("updated")
    }

    @Test
    fun `update replaces the content, the parameter schema and whether it reads the template's data`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))
        CreateStencil(id = id, name = "Header", content = letterhead()).execute()
        val schema = ObjectMapper().readTree("""{"type":"object","properties":{"name":{"type":"string"}}}""")

        UpdateStencil(id = id, content = letterhead(logo = "NEW LOGO"), parameterSchema = schema, readsTemplateData = true).execute()

        val stored = GetStencil(id = id).query()!!
        assertThat(stored.content.texts()).containsExactly("NEW LOGO")
        assertThat(stored.parameterSchema).isEqualTo(schema)
        assertThat(stored.readsTemplateData).isTrue()

        UpdateStencil(id = id, clearParameterSchema = true).execute()
        assertThat(GetStencil(id = id).query()!!.parameterSchema).isNull()
    }

    @Test
    fun `an empty parameter schema is stored as none`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))
        val schema = ObjectMapper().readTree("""{"type":"object","properties":{"name":{"type":"string"}}}""")
        CreateStencil(id = id, name = "Header", content = letterhead(), parameterSchema = ObjectMapper().readTree("{}")).execute()
        assertThat(GetStencil(id = id).query()!!.parameterSchema).`as`("created with an empty schema").isNull()

        UpdateStencil(id = id, parameterSchema = schema).execute()
        UpdateStencil(id = id, content = letterhead(logo = "NEW LOGO")).execute()
        assertThat(GetStencil(id = id).query()!!.parameterSchema).`as`("an update leaving it out keeps it").isEqualTo(schema)

        UpdateStencil(id = id, parameterSchema = ObjectMapper().readTree("""{"type":"object","properties":{}}""")).execute()
        assertThat(GetStencil(id = id).query()!!.parameterSchema).`as`("a schema without properties clears it").isNull()
    }

    @Test
    fun `a stencil may contain another stencil`() = test {
        val tenantId = TenantId(createTenant("Test Tenant").id)
        val inner = stencilId(tenantId)
        CreateStencil(id = inner, name = "Inner", content = letterhead()).execute()
        val outer = stencilId(tenantId)

        CreateStencil(id = outer, name = "Outer", content = templateFilling(inner)).execute()

        assertThat(GetStencil(id = outer).query()!!.content.nodes.values.map { it.type }).contains("stencil")
    }

    @Test
    fun `delete stencil`() = test {
        val id = stencilId(TenantId(createTenant("Test Tenant").id))
        CreateStencil(id = id, name = "To Delete", content = letterhead()).execute()

        assertThat(DeleteStencil(id = id).execute()).isTrue()

        assertThat(GetStencil(id = id).query()).isNull()
    }

    @Test
    fun `tenant isolation - cannot see other tenant stencils`() = test {
        val tenantId1 = TenantId(createTenant("Tenant 1").id)
        val tenantId2 = TenantId(createTenant("Tenant 2").id)

        CreateStencil(id = stencilId(tenantId1), name = "T1 Stencil").execute()
        CreateStencil(id = stencilId(tenantId2), name = "T2 Stencil").execute()

        assertThat(ListStencils(tenantId = tenantId1).query().map { it.name }).containsExactly("T1 Stencil")
        assertThat(ListStencils(tenantId = tenantId2).query().map { it.name }).containsExactly("T2 Stencil")
    }

    // ── By reference ──

    @Test
    fun `a template renders the stencil as it is now`() = test {
        val tenantId = TenantId(createTenant("Render By Reference").id)
        val stencil = stencilId(tenantId)
        CreateStencil(id = stencil, name = "Letterhead", content = letterhead(logo = "OLD LOGO")).execute()
        val variant = draftUsing(tenantId, templateFilling(stencil))

        assertThat(previewText(variant)).contains("OLD LOGO", "Dear customer")

        UpdateStencil(id = stencil, content = letterhead(logo = "NEW LOGO")).execute()

        assertThat(previewText(variant)).contains("NEW LOGO", "Dear customer").doesNotContain("OLD LOGO")
    }

    @Test
    fun `the editor and quality checks see the stencil inlined, with its parameter schema`() = test {
        val tenantId = TenantId(createTenant("Editor Context").id)
        val stencil = stencilId(tenantId)
        CreateStencil(id = stencil, name = "Letterhead", content = letterhead(logo = "EDITOR LOGO")).execute()
        val schema = ObjectMapper().readTree("""{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""")
        UpdateStencil(id = stencil, parameterSchema = schema).execute()
        val variant = draftUsing(tenantId, templateFilling(stencil))

        val context = GetEditorContext(variant).query()!!

        assertThat(context.templateModel.texts()).containsExactlyInAnyOrder("EDITOR LOGO", "Dear customer")
        // Quality sources read the instance's schema snapshot, which only the expanded form carries.
        assertThat(context.templateModel.nodes.getValue("inst").props!![StencilNodeKeys.PROP_PARAMETER_SCHEMA_SNAPSHOT])
            .isEqualTo(mapOf("type" to "object", "properties" to mapOf("name" to mapOf("type" to "string")), "required" to listOf("name")))
    }

    @Test
    fun `the usage page lists the templates using the stencil and whether each still fits`() = test {
        val tenantId = TenantId(createTenant("Usage").id)
        val stencil = stencilId(tenantId)
        CreateStencil(id = stencil, name = "Letterhead", content = letterhead()).execute()
        draftUsing(tenantId, templateFilling(stencil))
        draftUsing(tenantId, stencilDocument { build() })

        val before = GetStencilUsagePage(stencil).query()
        assertThat(before.total).isEqualTo(1)
        assertThat(before.items.single().instanceCount).isEqualTo(1)
        assertThat(before.items.single().fits).isTrue()

        // The stencil renames its placeholder: the template's fill no longer has a place.
        UpdateStencil(id = stencil, content = letterhead(placeholderName = "content")).execute()

        val after = GetStencilUsagePage(stencil).query().items.single()
        assertThat(after.fits).isFalse()
        assertThat(after.problems.single()).contains("'body'")
    }
}
