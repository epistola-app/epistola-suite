package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Environment counterpart of [ThemeCreateDialogUiTest] — proves the shared
 * create-dialog wiring works unchanged for the environment form. A fresh tenant
 * has no environments, so we open from the empty-state trigger… but the header
 * trigger is always present too; we use the header trigger for parity with the
 * theme test. A wide viewport keeps the sticky nav on one row so it does not
 * overlap that top-right action button's click point.
 */
class EnvironmentCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create environment via dialog navigates to the environments list`() {
        val tenant: Tenant = createTenant("Env Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/environments")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("environment-create-open"),
            "#create-environment-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-environment-form #name").fill("Production")
        assertThat(page.locator("#create-environment-form #slug")).hasValue("production")

        page.getByTestId("create-form-submit").click()

        assertThat(page).hasURL(Pattern.compile(".*/environments$"))
    }

    @Test
    fun `validation error keeps the dialog open with field errors`() {
        val tenant: Tenant = createTenant("Env Dialog Errors")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/environments")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("environment-create-open"),
            "#create-environment-dialog",
        )

        // The name field caps at 100 server-side but has no HTML5 maxlength, so
        // the form submits and the error returns from the server; a clean slug
        // isolates the failure to the name field.
        page.locator("#create-environment-form #name").fill("a".repeat(256))
        page.locator("#create-environment-form #slug").fill("valid-env")
        page.getByTestId("create-form-submit").click()

        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-environment-form .form-error")).isVisible()
    }
}
