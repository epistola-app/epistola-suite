package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat as assertThatJ

/**
 * Browser-level sentinel for the backend-driven font picker: the editor's
 * font dropdown must list the tenant's real (bundled `system`) font families
 * — not the old hardcoded CSS stacks — and selecting one must apply the
 * matching `@font-face` family to the rendered canvas.
 *
 * The handler/JSON path is covered by `FontRoutesTest`; this adds the rung
 * that the option list is wired into the inspector and the
 * `{slug,catalogKey}` → CSS family mapping reaches the canvas DOM.
 */
class FontPickerPlaywrightUiTest : BasePlaywrightTest() {

    private fun pmDoc(text: String): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )

    @Test
    fun `font dropdown lists system families and selecting one applies the font-face to the canvas`() {
        val editorUrl = withMediator { setupTemplateWithText() }

        gotoAndReady(editorUrl)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")

        // Select the single text block (by the node id we control) so the
        // inspector renders its styles.
        val textBlock = page.locator(".canvas-block[data-node-id='txt']").first()
        textBlock.click()

        // The font-family select is backend-driven; wait until the catalog
        // load has populated its options with the bundled "Inter" family.
        val fontSelect = page.locator("#inspector-style-fontFamily")
        fontSelect.waitFor()
        page.waitForFunction(
            """() => {
                const select = document.querySelector('#inspector-style-fontFamily');
                if (!select) return false;
                return Array.from(select.options).some((option) => option.textContent?.trim() === 'Inter');
            }""",
        )

        // Pick Inter by its visible label and assert the canvas content
        // element now resolves to the namespaced @font-face family.
        fontSelect.selectOption(
            com.microsoft.playwright.options.SelectOption().setLabel("Inter"),
        )

        val content = page.locator(".canvas-block-content").first()
        // styleMap writes the inline font-family; assert the computed value
        // contains the injected face name.
        page.waitForFunction(
            """() => {
                const el = document.querySelector('.canvas-block-content');
                if (!el) return false;
                return getComputedStyle(el).fontFamily.includes('epistola-system-inter');
            }""",
        )
        val fontFamily = content.evaluate("el => getComputedStyle(el).fontFamily") as String
        assertThatJ(fontFamily).contains("epistola-system-inter")
    }

    /**
     * Creates a tenant (its `system` font catalog is seeded on creation), a
     * template + variant whose draft has a single text node, and returns the
     * relative editor URL.
     */
    private fun setupTemplateWithText(): String {
        val tenant = CreateTenant(
            id = TenantKey.of("ui-font-${System.nanoTime()}"),
            name = "Font UI Test",
        ).execute()
        val tenantId = TenantId(tenant.id)

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Font Letter").execute()
        val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
        val variant = CreateVariant(
            id = variantId,
            title = "Default",
            description = null,
            attributes = emptyMap(),
        ).execute()!!

        val body = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "txt" to Node(
                    id = "txt",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("The quick brown fox")),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("txt")),
            ),
            themeRef = ThemeRef.Inherit,
        )
        UpdateDraft(variantId = variantId, templateModel = body).execute()

        return "/tenants/${tenant.id}/templates/default/${template.id}/variants/${variant.id}/editor"
    }
}
