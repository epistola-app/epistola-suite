package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * API-key create dialog. Unlike the other forms, a successful submit reveals the
 * secret once *inside* the dialog (the form is swapped for the reveal) rather
 * than navigating away.
 */
class ApiKeyCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `creating an api key reveals the secret in the dialog`() {
        val tenant: Tenant = createTenant("Api Key Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/api-keys")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("api-key-create-open"),
            "#create-api-key-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-api-key-dialog #name").fill("CI integration")
        page.getByTestId("create-form-submit").click()

        // The reveal swaps over the form inside the still-open dialog.
        assertThat(page.getByTestId("api-key-created")).isVisible()
        assertThat(page.locator("#api-key-secret")).not().hasValue("")
        assertThat(page.getByTestId("api-key-created-done")).isVisible()
    }
}
