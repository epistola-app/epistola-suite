package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Theme counterpart of [TemplateCreateDialogUiTest] — proves the shared
 * create-dialog wiring works unchanged for a second form. A fresh tenant already
 * carries a system theme, so the list is non-empty and we open from the header
 * trigger. A wide viewport keeps the sticky nav on one row so it does not overlap
 * that top-right action button's click point.
 */
class ThemeCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create theme via dialog navigates to the new theme`() {
        val tenant: Tenant = createTenant("Theme Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/themes")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("theme-create-open"),
            "#create-theme-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-theme-form #name").fill("Corporate")
        assertThat(page.locator("#create-theme-form #slug")).hasValue("corporate")

        page.getByTestId("create-form-submit").click()

        assertThat(page).hasURL(Pattern.compile(".*/themes/default/corporate$"))
    }

    @Test
    fun `validation error keeps the dialog open with field errors`() {
        val tenant: Tenant = createTenant("Theme Dialog Errors")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/themes")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("theme-create-open"),
            "#create-theme-dialog",
        )

        // The name field caps at 100 server-side but has no HTML5 maxlength, so
        // the form submits and the error returns from the server; a clean slug
        // isolates the failure to the name field.
        page.locator("#create-theme-form #name").fill("a".repeat(256))
        page.locator("#create-theme-form #slug").fill("valid-theme")
        page.getByTestId("create-form-submit").click()

        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-theme-form .form-error")).isVisible()
    }
}
