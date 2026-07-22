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

        // The always-present page-header action is the canonical trigger.
        assertThat(page.locator("[data-testid='template-create-open-action']")).isVisible()

        // Drive the empty-state trigger (fresh tenant → no templates → empty state
        // shown): it sits in the page body, clear of the sticky nav that would
        // otherwise intercept the pointer on the top-of-page header action. Both
        // triggers issue the same hx-get into #dialog-mount.
        page.openDialogByTrigger(
            page.locator("[data-testid='template-create-open']"),
            "#create-template-dialog",
        )

        assertThat(page.locator("dialog[open]#create-template-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }
}
