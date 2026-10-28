// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.default
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.microsoft.playwright.Mouse
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat as assertThatJ

class ExpressionClipboardPlaywrightUiTest : BasePlaywrightTest() {
    private val shortcutModifier = if (System.getProperty("os.name").startsWith("Mac")) "Meta" else "Control"

    @Test
    fun `expression-rich text supports browser selection copy paste and click clearing`() {
        val editorUrl = withMediator { setupTemplateWithExpression() }

        gotoAndReady(editorUrl)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")

        val block = page.locator(".canvas-block[data-node-id='text']")
        val proseMirror = block.locator(".ProseMirror")
        val expressionChips = proseMirror.locator(".expression-chip")
        val selectionStart = proseMirror.locator("strong").first()
        val selectionEnd = proseMirror.locator("em").first()
        proseMirror.waitFor()
        assertThat(expressionChips).hasCount(1)

        dragSelection(selectionStart, selectionEnd)
        assertThatJ(browserSelectionText().withoutWhitespace()).contains("Before{{customer.name}}after")

        // The copy is caught as the editor writes it, not read back from the system clipboard, which
        // the machine shares with everything else running: whatever writes to it between the copy and
        // the paste would be pasted instead.
        captureCopies()
        page.keyboard().press("$shortcutModifier+C")
        page.waitForFunction("() => window.__copied != null")
        val copiedText = page.evaluate("() => window.__copied.text") as String
        assertThatJ(copiedText.withoutWhitespace()).contains("Before{{customer.name}}after")

        // A normal click collapses the range and places the caret before the rich paste. Wait for
        // the collapse to land: ProseMirror takes the browser selection over from its
        // selectionchange handling, and a paste that arrives first replaces the old range instead
        // of inserting at the caret.
        selectionEnd.click()
        page.waitForFunction("() => window.getSelection()?.isCollapsed === true")
        page.keyboard().press("End")
        pasteCopied(proseMirror)
        assertThat(expressionChips).hasCount(2)

        dragSelection(selectionStart, selectionEnd)
        assertThatJ(browserSelectionCollapsed()).isFalse()
        block.locator(".canvas-block-header").click()
        page.waitForFunction("() => window.getSelection()?.isCollapsed === true")

        dragSelection(selectionStart, selectionEnd)
        expressionChips.first().click()
        assertThat(page.locator("ep-expression-dialog dialog")).isVisible()
        // The click on the chip clears the selection as the editor handles it; wait for that rather
        // than reading the selection once.
        page.waitForFunction("() => window.getSelection()?.isCollapsed ?? true")
    }

    private fun dragSelection(start: com.microsoft.playwright.Locator, end: com.microsoft.playwright.Locator) {
        val startBox = requireNotNull(start.boundingBox())
        val endBox = requireNotNull(end.boundingBox())
        page.mouse().move(startBox.x + 1, startBox.y + startBox.height / 2)
        page.mouse().down()
        page.mouse().move(
            endBox.x + endBox.width - 1,
            endBox.y + endBox.height / 2,
            Mouse.MoveOptions().setSteps(10),
        )
        page.mouse().up()
    }

    /** Keeps what the next copy puts on the clipboard, every flavour of it, in `window.__copied`. */
    private fun captureCopies() {
        page.evaluate(
            """() => {
                window.__copied = null;
                document.addEventListener('copy', (event) => {
                    const data = event.clipboardData;
                    window.__copied = { html: data.getData('text/html'), text: data.getData('text/plain') };
                }, { once: true });
            }""",
        )
    }

    /** Pastes what [captureCopies] kept into [target], as the browser hands a paste to the editor. */
    private fun pasteCopied(target: com.microsoft.playwright.Locator) {
        target.evaluate(
            """(element) => {
                const data = new DataTransfer();
                data.setData('text/html', window.__copied.html);
                data.setData('text/plain', window.__copied.text);
                element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
            }""",
        )
    }

    private fun browserSelectionText(): String = page.evaluate("() => window.getSelection()?.toString() ?? ''") as String

    private fun browserSelectionCollapsed(): Boolean = page.evaluate("() => window.getSelection()?.isCollapsed ?? true") as Boolean

    private fun String.withoutWhitespace(): String = replace(Regex("\\s+"), "")

    private fun setupTemplateWithExpression(): String {
        val tenant = TestNamespaces.createTenant(TenantKey.of("ui-expression-clipboard-${System.nanoTime()}"), "Expression Clipboard UI Test").execute()
        val tenantId = TenantId(tenant.id)

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Expression Clipboard").execute()
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
                "text" to Node(
                    id = "text",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to expressionDocument()),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("text")),
            ),
            themeRef = ThemeRef.Inherit,
        )
        UpdateVariantContent(variantId = variantId, templateModel = body).execute()

        return "/tenants/${tenant.id}/catalogs/default/templates/${template.id}/variants/${variant.id}/editor"
    }

    private fun expressionDocument(): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(
                    mapOf(
                        "type" to "text",
                        "marks" to listOf(mapOf("type" to "strong")),
                        "text" to "Before ",
                    ),
                    mapOf(
                        "type" to "expression",
                        "attrs" to mapOf("expression" to "customer.name", "isNew" to false),
                    ),
                    mapOf(
                        "type" to "text",
                        "marks" to listOf(mapOf("type" to "em")),
                        "text" to " after",
                    ),
                ),
            ),
        ),
    )
}
