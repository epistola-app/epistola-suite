// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import app.epistola.suite.ui.openDialogByTrigger
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class EnvironmentDialogUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("Environment Dialog Test")

    private val listUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/environments$")
    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/environments/new$")

    @Test
    fun `dialog is not open on initial load of the environments list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        // The create dialog is only rendered when the form is embedded/opened
        // (openDialog=true or swapped into #dialog-mount). On the plain list
        // route the mount is empty, so the create dialog must not be attached.
        assertThat(page.locator("#create-environment-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `clicking the New Environment trigger opens the create dialog`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        // The always-present page-header action is the canonical trigger.
        assertThat(page.locator("[data-testid='environment-create-open-action']")).isVisible()

        // Click a create trigger and let behaviors.js open the swapped-in dialog.
        // We drive the empty-state trigger (fresh tenant → no environments → empty
        // state shown): it sits in the page body, clear of the 48px sticky nav
        // that would otherwise intercept the pointer at the top-of-page header
        // action. Both triggers issue the same hx-get into #dialog-mount, so this
        // exercises the identical open path.
        page.openDialogByTrigger(
            page.locator("[data-testid='environment-create-open']"),
            "#create-environment-dialog",
        )

        assertThat(page.locator("#create-environment-dialog")).isVisible()
        assertThat(page.locator("dialog[open]#create-environment-dialog")).isVisible()
    }

    @Test
    fun `opening the create dialog pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        page.openDialogByTrigger(
            page.locator("[data-testid='environment-create-open']"),
            "#create-environment-dialog",
        )

        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `closing the dialog via Cancel restores the list URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        val dialog = page.openDialogByTrigger(
            page.locator("[data-testid='environment-create-open']"),
            "#create-environment-dialog",
        )
        assertThat(page).hasURL(newFormUrlPattern)

        // Cancel closes the dialog; behaviors.js replaceState-restores the list URL.
        dialog.locator("[data-close-dialog='create-environment-dialog']").click()

        assertThat(page).hasURL(listUrlPattern)
        assertThat(page.locator("#create-environment-dialog")).hasCount(0)
    }

    @Test
    fun `pressing Back after opening restores the list and closes the dialog`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        page.openDialogByTrigger(
            page.locator("[data-testid='environment-create-open']"),
            "#create-environment-dialog",
        )
        assertThat(page).hasURL(newFormUrlPattern)

        // Back exercises htmx's native boost popstate/snapshot restore.
        page.goBack()

        assertThat(page).hasURL(listUrlPattern)
        assertThat(page.locator("#create-environment-dialog")).hasCount(0)
    }
}
