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
 * Browser contract for the application dialogs (ADR 0054): create an application, which lands on
 * its page, then create an API key there. The key dialog's distinctive feature is the REVEAL on
 * success: the one-time plaintext key panel is swapped into the dialog in place of the form
 * (dialogReveal) and the dialog STAYS OPEN so the user can copy the key before dismissing it.
 *
 * Like the sibling *DialogUiTests, the page-header action sits under the 48px sticky nav and would
 * intercept a synthetic pointer click. We activate triggers via the keyboard instead — the same
 * `click` event, dispatched without a pointer — and assert readiness web-first.
 */
class ApplicationDialogUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("Application Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/applications/new$")
    private val detailUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/applications/[0-9a-f-]{36}$")
    private val newKeyUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/applications/[0-9a-f-]{36}/keys/new$")

    private fun openDialog(triggerTestId: String, dialogId: String): Locator {
        val trigger = page.locator("[data-testid='$triggerTestId']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#$dialogId")).isVisible()
        return page.locator("#$dialogId")
    }

    @Test
    fun `dialog is not open on initial load of the applications list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/applications")
        page.htmxSettle()

        assertThat(page.locator("#create-application-dialog")).hasCount(0)
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `the old api-keys address leads to the applications list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/api-keys")

        assertThat(page).hasURL(Pattern.compile(".*/tenants/[^/]+/applications$"))
    }

    @Test
    fun `activating the New application trigger opens the dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/applications")
        page.htmxSettle()

        openDialog("application-create-open-action", "create-application-dialog")

        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `creating an application lands on its page, where a new key is revealed once and the dialog stays open`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/applications")
        page.htmxSettle()

        // The default Viewer role is pre-checked, so a name is all the form needs to be valid.
        val createDialog = openDialog("application-create-open-action", "create-application-dialog")
        createDialog.locator("#name").fill("CI integration")
        createDialog.locator("[data-testid='create-form-submit']").click()

        assertThat(page).hasURL(detailUrlPattern)
        page.htmxSettle()

        val keyDialog = openDialog("api-key-create-open", "create-api-key-dialog")
        assertThat(page).hasURL(newKeyUrlPattern)
        keyDialog.locator("#name").fill("CI key")
        keyDialog.locator("[data-testid='create-form-submit']").click()

        // REVEAL: the dialog stays open, the form is replaced by the key panel.
        assertThat(page.locator("dialog[open]#create-api-key-dialog")).isVisible()
        assertThat(page.locator("#api-key-secret")).isVisible()
        assertThat(page.locator("#api-key-copy-btn")).isVisible()
    }
}
