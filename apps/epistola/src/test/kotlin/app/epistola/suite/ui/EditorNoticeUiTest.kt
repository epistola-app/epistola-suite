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
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestIdHelpers
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.junit.jupiter.api.Test

/**
 * Corner notices on the template editor page (#477). The editor renders its
 * own `<body>` (no layout/shell), hosting the #notices region + template pair
 * itself — and its dialogs are Lit-rendered, removed from the DOM outright on
 * close. That removal is the real-world case the notices.js MutationObserver
 * rescue exists for; NoticeModalPlacementUiTest simulates it on a shell page,
 * this test exercises the genuine Lit unmount.
 */
class EditorNoticeUiTest : BasePlaywrightTest() {

    @Test
    fun `the editor page hosts a working notice region`() {
        gotoEditor("Editor Notice Region")

        page.evaluate("() => window.epistolaNotice.error('Editor notices work.')")

        val notice = page.locator("body > #notices .notice")
        assertThat(notice).isVisible()
        assertThat(notice).containsText("Editor notices work.")
    }

    @Test
    fun `a notice survives the stencil dialog's real Lit unmount`() {
        gotoEditor("Editor Notice Lit Rescue")

        // The stencil picker is a Lit-rendered modal <dialog>.
        page.openDialogByTrigger(
            page.getByTestId("palette-item-stencil"),
            "dialog.stencil-picker-dialog",
        )
        page.evaluate("() => window.epistolaNotice.error('Survives the Lit unmount.')")
        assertThat(page.locator("dialog.stencil-picker-dialog #notices .notice")).isVisible()

        // Cancel closes AND removes the dialog element — no htmx event, no
        // toggle-close before removal. The MutationObserver rescue must
        // re-home the region with the live notice.
        page.locator("dialog.stencil-picker-dialog")
            .getByRole(AriaRole.BUTTON, com.microsoft.playwright.Locator.GetByRoleOptions().setName("Cancel"))
            .click()

        val rescued = page.locator("body > #notices:not([popover]) .notice")
        assertThat(rescued).isVisible()
        assertThat(rescued).containsText("Survives the Lit unmount.")
    }

    private fun gotoEditor(name: String) {
        val (tenantKey, templateKey) = withMediator {
            val tenant = CreateTenant(
                id = TenantKey.of("editor-notice-${System.nanoTime()}"),
                name = name,
            ).execute()
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = name).execute()
            tenant.id.value to templateId.key.value
        }
        gotoAndReady("/tenants/$tenantKey/templates/default/$templateKey/variants/initial/editor")
    }
}
