package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Covers the create-template dialog flow: the list-page trigger loads the
 * dialog into #dialog-host, the wiring opens it, slug auto-fill works on the
 * dynamically injected fields, and a successful submit navigates to the new
 * template via HX-Redirect. A validation failure re-renders the form inside
 * the still-open dialog.
 *
 * Both tests open from the empty-state trigger: the header action sits behind
 * the fixed nav in the headless viewport, so a click there lands on the nav.
 */
class TemplateCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create template via dialog navigates to the new template`() {
        val tenant: Tenant = createTenant("Template Dialog Test")

        gotoAndReady("/tenants/${tenant.id}/templates")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-template-form #name").fill("Invoice Run")
        // Auto-slug runs on the injected fields (MutationObserver in slug-auto.js).
        assertThat(page.locator("#create-template-form #slug")).hasValue("invoice-run")

        page.getByTestId("create-form-submit").click()

        // HX-Redirect lands on the new template's detail page.
        assertThat(page).hasURL(Pattern.compile(".*/templates/default/invoice-run$"))
    }

    @Test
    fun `validation error keeps the dialog open with field errors`() {
        val tenant: Tenant = createTenant("Template Dialog Errors")

        gotoAndReady("/tenants/${tenant.id}/templates")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )

        // A 256-char name passes the browser's constraints (the field has no
        // maxlength) but the command rejects it, so the form actually submits
        // and the error comes back from the server. A clean slug isolates the
        // failure to the name field.
        page.locator("#create-template-form #name").fill("a".repeat(256))
        page.locator("#create-template-form #slug").fill("valid-slug")
        page.getByTestId("create-form-submit").click()

        // The form re-renders inside the still-open dialog with the error.
        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-template-form .form-error")).isVisible()
    }
}
