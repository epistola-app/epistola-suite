// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * CR7: a list can carry filter/sort/paging state in its URL query string. Opening a
 * create dialog pushes the bare /…/new URL (hx-push-url), which drops that query;
 * cancelling must return to the list URL WITH its query, not the unfiltered path.
 *
 * The fix lives in the shared behaviors.js close listener, so the environments list
 * (whose handler simply ignores the query) is a sufficient, self-contained probe of
 * the mechanism.
 */
class DialogQueryPreservationUiTest : BasePlaywrightTest() {

    @Test
    fun `cancelling a create dialog restores the list query string`() {
        val tenant = createTenant("Dialog Query Preserve")
        gotoAndReady("/tenants/${tenant.id}/environments?catalog=marketing&sort=name")
        page.htmxSettle()

        // Open the create dialog off the header action (keyboard, per sticky-nav).
        // Opening pushes /environments/new, dropping the query from the address bar.
        val trigger = page.locator("[data-testid='environment-create-open-action']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#create-environment-dialog")).isVisible()

        // Cancel via ESC → the list URL is restored WITH its query preserved (CR7).
        page.keyboard().press("Escape")
        assertThat(page.locator("#create-environment-dialog")).hasCount(0)
        Assertions.assertThat(page.url())
            .endsWith("/tenants/${tenant.id}/environments?catalog=marketing&sort=name")
    }
}
