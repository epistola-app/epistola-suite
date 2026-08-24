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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class ColumnsLayoutPlaywrightUiTest : BasePlaywrightTest() {

    @Test
    fun `column content retains its editor inset`() {
        val editorUrl = withMediator { setupTemplateWithColumns() }

        gotoAndReady(editorUrl)
        page.getByTestId("editor-container").waitFor()
        page.waitForSelector("epistola-editor")

        val siblingContent = page.locator(".canvas-block[data-node-id='sibling'] .ProseMirror")
        val firstColumnContent = page.locator(".canvas-block[data-node-id='first-column'] .ProseMirror")
        val columnsContent = page.locator(
            ".canvas-block[data-node-id='columns'] > .canvas-block-content",
        )
        siblingContent.waitFor()
        firstColumnContent.waitFor()

        val columnsPaddingLeft = columnsContent.evaluate(
            "element => parseFloat(getComputedStyle(element).paddingLeft)",
        ) as Number
        val siblingBox = requireNotNull(siblingContent.boundingBox())
        val firstColumnBox = requireNotNull(firstColumnContent.boundingBox())
        assertThat(columnsPaddingLeft.toDouble()).isPositive()
        // The extra pixel is the editor chrome's border around the columns content area.
        assertThat(firstColumnBox.x - siblingBox.x)
            .isCloseTo(columnsPaddingLeft.toDouble() + 1.0, within(1.5))
    }

    private fun setupTemplateWithColumns(): String {
        val tenant = CreateTenant(
            id = TenantKey.of("ui-columns-${System.nanoTime()}"),
            name = "Columns Layout UI Test",
        ).execute()
        val tenantId = TenantId(tenant.id)

        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        val template = CreateDocumentTemplate(id = templateId, name = "Columns Layout").execute()
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
                "sibling" to Node(
                    id = "sibling",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("Sibling")),
                ),
                "columns" to Node(
                    id = "columns",
                    type = "columns",
                    slots = listOf("first-slot", "second-slot"),
                    props = mapOf("columnSizes" to listOf(1, 1), "gap" to 0),
                ),
                "first-column" to Node(
                    id = "first-column",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to pmDoc("First column")),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("sibling", "columns")),
                "first-slot" to Slot("first-slot", "columns", "column-0", listOf("first-column")),
                "second-slot" to Slot("second-slot", "columns", "column-1", emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
        UpdateDraft(variantId = variantId, templateModel = body).execute()

        return "/tenants/${tenant.id}/templates/default/${template.id}/variants/${variant.id}/editor"
    }

    private fun pmDoc(text: String): Map<String, Any> = mapOf(
        "type" to "doc",
        "content" to listOf(
            mapOf(
                "type" to "paragraph",
                "content" to listOf(mapOf("type" to "text", "text" to text)),
            ),
        ),
    )
}
