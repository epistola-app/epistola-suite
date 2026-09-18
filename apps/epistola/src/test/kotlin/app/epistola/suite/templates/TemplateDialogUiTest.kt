// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import app.epistola.suite.ui.openDialogByTrigger
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class TemplateDialogUiTest : BasePlaywrightTest() {

    // A fresh tenant already owns the auto-created authored "Default" catalog, so
    // the create dialog's catalog <select> is never empty.
    private fun createTestTenant(): Tenant = createTenant("Template Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/templates/new$")

    @Test
    fun `dialog is not open on initial load of the templates list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.htmxSettle()

        // On the plain list route the mount is empty, so the create dialog must
        // not be attached (guards the th:if/th:replace precedence trap).
        assertThat(page.locator("#create-template-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `clicking the New Template trigger opens the create dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.htmxSettle()

        // The page-header action is the canonical trigger. The empty state repeats it as
        // onboarding, which is deliberate — both issue the same hx-get into #dialog-mount.
        assertThat(page.locator("[data-testid='template-create-open']")).isVisible()

        // Drive the header action directly. It is reachable because html carries
        // scroll-padding-top: var(--ep-sticky-offset), so scrollIntoViewIfNeeded leaves it
        // clear of the sticky .app-nav that used to intercept the pointer here.
        page.openDialogByTrigger(
            page.locator("[data-testid='template-create-open-action']"),
            "#create-template-dialog",
        )

        assertThat(page.locator("dialog[open]#create-template-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }
}
