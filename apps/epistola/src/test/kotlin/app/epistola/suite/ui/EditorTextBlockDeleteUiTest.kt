// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.default
import app.epistola.suite.testing.withRequiredDataExample
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A text block is deleted with the trash button in its header. While the text had focus, the
 * formatting menu floated just above the caret, which on the first line is right over that header,
 * so the button could not be clicked. The menu now keeps clear of the block's own header.
 */
class EditorTextBlockDeleteUiTest : BasePlaywrightTest() {

    @Test
    fun `a text block being typed in can be deleted from its header`() {
        val tenantKey = TenantKey.of("test-editor-text-delete-${System.nanoTime()}")
        val template = withMediator {
            TestNamespaces.createTenant(tenantKey, "Text Delete Tenant").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenantKey)))
            CreateDocumentTemplate(id = templateId, name = "Text Delete", catalogVersion = null).execute().withRequiredDataExample()
        }
        gotoAndReady("/tenants/$tenantKey/catalogs/default/templates/${template.id}/variants/initial/editor")
        page.waitForSelector("epistola-editor")

        page.getByTestId("palette-item-text").click()
        val text = page.locator(".ProseMirror").first()
        text.click()
        page.keyboard().type("Dear customer")
        // The formatting menu is up while the text has focus: the situation that hid the button.
        assertThat(page.locator(".pm-bubble-menu")).isVisible()

        // Playwright clicks only an element nothing covers, so this fails while the menu overlaps it.
        page.locator(".canvas-block.selected .canvas-block-delete").click()

        assertThat(page.getByTestId("canvas-block")).hasCount(0)
    }
}
