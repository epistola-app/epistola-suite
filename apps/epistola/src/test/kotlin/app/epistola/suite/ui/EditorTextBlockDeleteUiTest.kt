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
 * A text block's header is its handle: clicking it selects the block, it does not start typing.
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

    @Test
    fun `clicking a text block's header selects it without starting to type`() {
        val tenantKey = TenantKey.of("test-editor-text-header-${System.nanoTime()}")
        val template = withMediator {
            TestNamespaces.createTenant(tenantKey, "Text Header Tenant").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenantKey)))
            CreateDocumentTemplate(id = templateId, name = "Text Header", catalogVersion = null).execute().withRequiredDataExample()
        }
        gotoAndReady("/tenants/$tenantKey/catalogs/default/templates/${template.id}/variants/initial/editor")
        page.waitForSelector("epistola-editor")
        page.getByTestId("palette-item-text").click()

        // The header is the block's handle: it selects the block, and the text does not take focus,
        // so no formatting menu comes up over the header's own buttons.
        page.getByTestId("canvas-block").first().locator(".canvas-block-header").click()
        assertThat(page.locator(".canvas-block.selected")).hasCount(1)
        assertThat(page.locator(".ProseMirror:focus")).hasCount(0)
        assertThat(page.locator(".pm-bubble-menu")).isHidden()

        // The text itself is where typing starts.
        page.locator(".ProseMirror").first().click()
        assertThat(page.locator(".ProseMirror:focus")).hasCount(1)
        assertThat(page.locator(".pm-bubble-menu")).isVisible()
    }

    @Test
    fun `clicking the header lets go of selected text`() {
        val tenantKey = TenantKey.of("test-editor-text-release-${System.nanoTime()}")
        val template = withMediator {
            TestNamespaces.createTenant(tenantKey, "Text Release Tenant").execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenantKey)))
            CreateDocumentTemplate(id = templateId, name = "Text Release", catalogVersion = null).execute().withRequiredDataExample()
        }
        gotoAndReady("/tenants/$tenantKey/catalogs/default/templates/${template.id}/variants/initial/editor")
        page.waitForSelector("epistola-editor")
        page.getByTestId("palette-item-text").click()
        page.locator(".ProseMirror").first().click()
        page.keyboard().type("Dear customer")
        page.keyboard().press("Shift+Home")
        page.waitForFunction("() => window.getSelection()?.toString() === 'Dear customer'")

        // Pressing the handle is about the block, not the text: nothing stays highlighted or focused.
        page.locator(".canvas-block.selected .canvas-block-header").click()
        page.waitForFunction("() => window.getSelection()?.isCollapsed ?? true")
        assertThat(page.locator(".ProseMirror:focus")).hasCount(0)
        assertThat(page.locator(".pm-bubble-menu")).isHidden()
    }
}
