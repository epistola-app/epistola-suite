// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser contract for the code-list create dialog (redirect-to-detail conversion
 * with a sourceType radio-pane cascade). Mirrors the other *DialogUiTests and
 * additionally proves the radio-pane cascade works INSIDE the swapped-in dialog:
 * after opening, selecting the "URL" radio reveals the source-url pane.
 *
 * Like attributes, the only always-present trigger is the page-header action,
 * which sits under the 48px sticky nav and would intercept a synthetic pointer
 * click. We activate it via the keyboard instead — the same `click` event,
 * dispatched without a pointer — and assert dialog readiness web-first.
 */
class CodeListDialogUiTest : BasePlaywrightTest() {

    // A fresh tenant already owns the auto-created authored "Default" catalog, so
    // the create dialog's catalog <select> is never empty.
    private fun createTestTenant(): Tenant = createTenant("Code List Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/code-lists/new$")

    /**
     * Opens the create dialog off the always-present page-header action via keyboard
     * activation (see class KDoc for why a pointer click is unusable here), then
     * asserts the modal is open web-first. Returns the dialog locator.
     */
    private fun openCreateDialog(): Locator {
        val trigger = page.locator("[data-testid='code-list-create-open-action']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#create-code-list-dialog")).isVisible()
        return page.locator("#create-code-list-dialog")
    }

    @Test
    fun `dialog is not open on initial load of the code-lists list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/code-lists")
        page.htmxSettle()

        // The create dialog is only attached when the form is embedded/opened
        // (openDialog=true or swapped into #dialog-mount). On the plain list route
        // the mount is empty, so the create dialog must not be present.
        assertThat(page.locator("#create-code-list-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `activating the New Code List trigger opens the dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/code-lists")
        page.htmxSettle()

        openCreateDialog()

        assertThat(page.locator("dialog[open]#create-code-list-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `selecting the URL source reveals the source-url pane inside the dialog`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/code-lists")
        page.htmxSettle()

        val dialog = openCreateDialog()

        // The URL pane starts hidden (INLINE is the default source type).
        val urlPane = dialog.locator("[data-source-pane='URL']")
        assertThat(urlPane).isHidden()

        // Selecting the "URL" radio runs the delegated radio-pane cascade
        // (pages/asset-forms.js) — which works for the swapped-in dialog because
        // the change listener is delegated on document — so the pane becomes visible.
        dialog.locator("input[name='sourceType'][value='URL']").check()

        assertThat(urlPane).isVisible()
    }
}
