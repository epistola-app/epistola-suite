// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser contract for the attribute create dialog (stay-on-list conversion with
 * a radio-pane cascade). Mirrors the other *DialogUiTests and additionally proves
 * the constraintKind radio-pane cascade works INSIDE the swapped-in dialog: after
 * opening, selecting the "inline" radio reveals the allowed-values pane.
 *
 * Attribute-specific twist: a fresh tenant always carries SUBSCRIBED system-catalog
 * attributes, so the list is never empty and there is no body-level empty-state
 * trigger like the environments/templates tests click. The only trigger is the
 * page-header action, which sits under the 48px sticky nav and would intercept a
 * synthetic pointer click. We activate it via the keyboard instead — the same
 * `click` event, dispatched without a pointer — and assert dialog readiness
 * web-first (equivalent to what openDialogByTrigger guarantees).
 */
class AttributeDialogUiTest : BasePlaywrightTest() {

    // A fresh tenant already owns the auto-created authored "Default" catalog, so
    // the create dialog's catalog <select> is never empty.
    private fun createTestTenant(): Tenant = createTenant("Attribute Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/attributes/new$")

    /**
     * Opens the create dialog off the always-present page-header action via keyboard
     * activation (see class KDoc for why a pointer click is unusable here), then
     * asserts the modal is open web-first. Returns the dialog locator.
     */
    private fun openCreateDialog(): Locator {
        val trigger = page.locator("[data-testid='attribute-create-open-action']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#create-attribute-dialog")).isVisible()
        return page.locator("#create-attribute-dialog")
    }

    @Test
    fun `dialog is not open on initial load of the attributes list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/attributes")
        page.htmxSettle()

        // The create dialog is only attached when the form is embedded/opened
        // (openDialog=true or swapped into #dialog-mount). On the plain list route
        // the mount is empty, so the create dialog must not be present.
        assertThat(page.locator("#create-attribute-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `activating the New Attribute trigger opens the dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/attributes")
        page.htmxSettle()

        openCreateDialog()

        assertThat(page.locator("dialog[open]#create-attribute-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `selecting the inline constraint reveals the allowed-values pane inside the dialog`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/attributes")
        page.htmxSettle()

        val dialog = openCreateDialog()

        // The inline pane starts hidden (free is the default constraint).
        val inlinePane = dialog.locator("[data-constraint-pane='inline']")
        assertThat(inlinePane).isHidden()

        // Selecting the "inline" radio runs the delegated radio-pane cascade
        // (pages/asset-forms.js) — which works for the swapped-in dialog because
        // the change listener is delegated on document — so the pane becomes visible.
        dialog.locator("input[name='constraintKind'][value='inline']").check()

        assertThat(inlinePane).isVisible()
    }
}
