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
 * Browser contract for the API-key create dialog. Its distinctive feature is the
 * REVEAL on success: the one-time plaintext key panel is swapped into the dialog
 * in place of the form (dialogReveal) and the dialog STAYS OPEN so the user can
 * copy the key before dismissing it.
 *
 * Like the sibling *DialogUiTests, the only always-present trigger is the
 * page-header action, which sits under the 48px sticky nav and would intercept a
 * synthetic pointer click. We activate it via the keyboard instead — the same
 * `click` event, dispatched without a pointer — and assert readiness web-first.
 */
class ApiKeyDialogUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("API Key Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/api-keys/new$")

    /**
     * Opens the create dialog off the always-present page-header action via keyboard
     * activation (see class KDoc for why a pointer click is unusable here), then
     * asserts the modal is open web-first. Returns the dialog locator.
     */
    private fun openCreateDialog(): Locator {
        val trigger = page.locator("[data-testid='api-key-create-open-action']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#create-api-key-dialog")).isVisible()
        return page.locator("#create-api-key-dialog")
    }

    @Test
    fun `dialog is not open on initial load of the api-keys list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/api-keys")
        page.htmxSettle()

        // On the plain list route the mount is empty, so the create dialog must
        // not be present.
        assertThat(page.locator("#create-api-key-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `activating the New API key trigger opens the dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/api-keys")
        page.htmxSettle()

        openCreateDialog()

        assertThat(page.locator("dialog[open]#create-api-key-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `submitting the form reveals the one-time key and keeps the dialog open`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/api-keys")
        page.htmxSettle()

        val dialog = openCreateDialog()

        // The default Viewer role checkbox is pre-checked, so a name is all the
        // form needs to be valid.
        dialog.locator("#name").fill("CI integration")
        dialog.locator("[data-testid='create-form-submit']").click()

        // REVEAL: the dialog stays open, the form is replaced by the key panel.
        assertThat(page.locator("dialog[open]#create-api-key-dialog")).isVisible()
        assertThat(page.locator("#api-key-secret")).isVisible()
        assertThat(page.locator("#api-key-copy-btn")).isVisible()
    }
}
