package app.epistola.suite.handlers

import app.epistola.suite.tenants.Tenant
import app.epistola.suite.ui.BasePlaywrightTest
import app.epistola.suite.ui.htmxSettle
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Browser contract for the catalog create dialog (server-sent, URL-addressable,
 * STAY-ON-LIST conversion). Mirrors the other *DialogUiTests: the dialog is not
 * attached on the plain list route, and activating the "New Catalog" trigger
 * swaps it in and pushes the shareable /…/new URL.
 *
 * The only always-present trigger is the page-header action, which sits under the
 * 48px sticky nav and would intercept a synthetic pointer click. We activate it
 * via the keyboard instead — the same `click` event, dispatched without a pointer.
 */
class CatalogDialogUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("Catalog Dialog Test")

    private val newFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/catalogs/new$")

    private val registerFormUrlPattern: Pattern = Pattern.compile(".*/tenants/[^/]+/catalogs/register$")

    @Test
    fun `dialog is not open on initial load of the catalogs list`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/catalogs")
        page.htmxSettle()

        // The create dialog is only attached when embedded/opened (openDialog=true
        // or swapped into #dialog-mount). On the plain list route the mount is empty.
        assertThat(page.locator("#create-catalog-dialog")).hasCount(0)
        assertThat(page.locator("dialog[open]")).hasCount(0)
    }

    @Test
    fun `activating the New Catalog trigger opens the dialog and pushes the new-form URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/catalogs")
        page.htmxSettle()

        val trigger = page.locator("[data-testid='catalog-create-open']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")

        assertThat(page.locator("dialog[open]#create-catalog-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/new URL via hx-push-url.
        assertThat(page).hasURL(newFormUrlPattern)
    }

    @Test
    fun `activating the Subscribe trigger opens the dialog and pushes the register URL`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/catalogs")
        page.htmxSettle()

        val trigger = page.locator("[data-testid='catalog-subscribe-open']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")

        assertThat(page.locator("dialog[open]#subscribe-catalog-dialog")).isVisible()
        // Opening the dialog pushes the shareable /…/register URL via hx-push-url.
        assertThat(page).hasURL(registerFormUrlPattern)
    }
}
