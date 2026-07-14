package app.epistola.suite.themes

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import app.epistola.suite.ui.openDialogByTrigger
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class ThemeDialogUiTest : BasePlaywrightTest() {

    // A fresh tenant already owns the auto-created authored "Default" catalog, so
    // the create dialog's catalog <select> is never empty.
    private fun createTestTenant(): Tenant = createTenant("Theme Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/themes/new$")

    @Test
    fun `dialog is not open on initial load of the themes list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/themes")
        page.htmxSettle()

        // On the plain list route the mount is empty, so the create dialog must
        // not be attached (guards the th:if/th:replace precedence trap).
        assertThat(page.locator("#create-theme-dialog")).hasCount(0)

        // Defensive: no modal dialog anywhere should be open on initial load.
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `clicking the New Theme trigger opens the create dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        // Unlike templates/environments, a fresh tenant already has subscribed
        // system themes, so the unfiltered list is never empty. Filter to the
        // (empty) authored `default` catalog so the empty-state "New Theme" trigger
        // renders in the page body — clear of the sticky nav that would otherwise
        // intercept the pointer on the top-of-page header action. Both triggers
        // issue the same hx-get into #dialog-mount, so this exercises the identical
        // open path (and the pushed /…/new URL).
        gotoAndReady("/tenants/${tenant.id}/themes?catalog=default")
        page.htmxSettle()

        // The always-present page-header action is the canonical trigger.
        assertThat(page.locator("[data-testid='theme-create-open-action']")).isVisible()

        page.openDialogByTrigger(
            page.locator("[data-testid='theme-create-open']"),
            "#create-theme-dialog",
        )

        assertThat(page.locator("dialog[open]#create-theme-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }
}
