package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * CR6: a stay-on-list create (dialogSuccess) OOB-refreshes the list UNFILTERED, but
 * the header search box lives outside the swapped region and keeps the user's term —
 * leaving a stale term above rows that no longer match it. The shared closeDialog
 * handler clears the list search box on a successful create-close (and only then;
 * Cancel/ESC never fire closeDialog, so a cancelled create keeps the search).
 */
class DialogSearchClearUiTest : BasePlaywrightTest() {

    @Test
    fun `a successful create clears a stale list search term`() {
        val tenant = createTenant("Dialog Search Clear")
        gotoAndReady("/tenants/${tenant.id}/environments")
        page.htmxSettle()

        // Leave a term in the list search box.
        val search = page.locator(".search-box input[name='q']")
        search.fill("zzz-no-match")
        page.htmxSettle()

        // Create an environment through the dialog (name auto-fills the slug).
        page.locator("[data-testid='environment-create-open-action']").press("Enter")
        assertThat(page.locator("dialog[open]#create-environment-dialog")).isVisible()
        page.locator("#name").fill("Production")
        page.locator("[data-testid='create-form-submit']").click()
        page.htmxSettle()

        // dialogSuccess closed the dialog and cleared the stale search term (CR6).
        assertThat(page.locator("#create-environment-dialog")).hasCount(0)
        assertThat(search).hasValue("")
    }
}
