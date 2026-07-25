// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import app.epistola.suite.ui.openDialogByTrigger
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class StencilDialogUiTest : BasePlaywrightTest() {

    // A fresh tenant already owns the auto-created authored "Default" catalog, so
    // the create dialog's catalog <select> is never empty.
    private fun createTestTenant(): Tenant = createTenant("Stencil Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/stencils/new$")

    @Test
    fun `dialog is not open on initial load of the stencils list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/stencils")
        page.htmxSettle()

        // On the plain list route the mount is empty, so the create dialog must
        // not be attached (guards the th:if/th:replace precedence trap).
        assertThat(page.locator("#create-stencil-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `clicking the New Stencil trigger opens the create dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        // A fresh tenant has no stencils, so the empty-state "New Stencil" trigger
        // renders in the page body — clear of the sticky nav that would otherwise
        // intercept the pointer on the top-of-page header action. Both triggers
        // issue the same hx-get into #dialog-mount, so this exercises the identical
        // open path (and the pushed /…/new URL).
        gotoAndReady("/tenants/${tenant.id}/stencils")
        page.htmxSettle()

        // The always-present page-header action is the canonical trigger.
        assertThat(page.locator("[data-testid='stencil-create-open-action']")).isVisible()

        page.openDialogByTrigger(
            page.locator("[data-testid='stencil-create-open']"),
            "#create-stencil-dialog",
        )

        assertThat(page.locator("dialog[open]#create-stencil-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }
}
