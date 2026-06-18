package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Stencil counterpart of [ThemeCreateDialogUiTest] — proves the shared
 * create-dialog wiring works unchanged for the stencil form. The header "New
 * Stencil" action is always present (it lives in the page actions, not the
 * empty state), so we open from the header trigger. A wide viewport keeps the
 * sticky nav on one row so it does not overlap that top-right button's click
 * point.
 */
class StencilCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create stencil via dialog navigates to the new stencil`() {
        val tenant: Tenant = createTenant("Stencil Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/stencils")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("stencil-create-open"),
            "#create-stencil-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-stencil-form #name").fill("Corporate Header")
        assertThat(page.locator("#create-stencil-form #slug")).hasValue("corporate-header")

        page.getByTestId("create-form-submit").click()

        assertThat(page).hasURL(Pattern.compile(".*/stencils/default/corporate-header$"))
    }

    @Test
    fun `validation error keeps the dialog open with field errors`() {
        val tenant: Tenant = createTenant("Stencil Dialog Errors")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/stencils")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("stencil-create-open"),
            "#create-stencil-dialog",
        )

        // The name field caps at 255 server-side but has no HTML5 maxlength, so
        // the form submits and the error returns from the server; a clean slug
        // (set after the name, marking it manually edited) isolates the failure
        // to the name field.
        page.locator("#create-stencil-form #name").fill("a".repeat(256))
        page.locator("#create-stencil-form #slug").fill("valid-stencil")
        page.getByTestId("create-form-submit").click()

        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-stencil-form .form-error[data-error='true']")).isVisible()
    }
}
