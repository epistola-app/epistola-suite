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
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
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
        page.context().grantPermissions(listOf("clipboard-read", "clipboard-write"))

        val block = page.locator(".canvas-block[data-node-id='text']")
        val proseMirror = block.locator(".ProseMirror")
        val expressionChips = proseMirror.locator(".expression-chip")
        val selectionStart = proseMirror.locator("strong").first()
        val selectionEnd = proseMirror.locator("em").first()
        proseMirror.waitFor()
        assertThat(expressionChips).hasCount(1)

        dragSelection(selectionStart, selectionEnd)
        assertThatJ(browserSelectionText().withoutWhitespace()).contains("Before{{customer.name}}after")

        page.keyboard().press("$shortcutModifier+C")
        val clipboardText = page.evaluate("() => navigator.clipboard.readText()") as String
        assertThatJ(clipboardText.withoutWhitespace()).contains("Before{{customer.name}}after")

        // A normal click collapses the range and places the caret before the rich paste.
        selectionEnd.click()
        page.keyboard().press("End")
        page.keyboard().press("$shortcutModifier+V")
        assertThat(expressionChips).hasCount(2)

        dragSelection(selectionStart, selectionEnd)
        assertThatJ(browserSelectionCollapsed()).isFalse()
        block.locator(".canvas-block-header").click()
        page.waitForFunction("() => window.getSelection()?.isCollapsed === true")

        dragSelection(selectionStart, selectionEnd)
        expressionChips.first().click()
        assertThat(page.locator("ep-expression-dialog dialog")).isVisible()
        assertThatJ(browserSelectionCollapsed()).isTrue()
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

    private fun browserSelectionText(): String = page.evaluate("() => window.getSelection()?.toString() ?? ''") as String

    private fun browserSelectionCollapsed(): Boolean = page.evaluate("() => window.getSelection()?.isCollapsed ?? true") as Boolean

    private fun String.withoutWhitespace(): String = replace(Regex("\\s+"), "")

    private fun setupTemplateWithExpression(): String {
        val tenant = CreateTenant(
            id = TenantKey.of("ui-expression-clipboard-${System.nanoTime()}"),
            name = "Expression Clipboard UI Test",
        ).execute()
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
        UpdateDraft(variantId = variantId, templateModel = body).execute()

        return "/tenants/${tenant.id}/templates/default/${template.id}/variants/${variant.id}/editor"
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
