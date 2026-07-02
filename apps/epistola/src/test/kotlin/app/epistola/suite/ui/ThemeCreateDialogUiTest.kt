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

        // The field enforces maxlength=100 in the browser (issue #633), so strip it
        // at runtime to push an over-length value through and prove the SERVER also
        // rejects it, rendering the field error in the open dialog (defense in depth).
        page.locator("#create-theme-form #name").evaluate("el => el.removeAttribute('maxlength')")
        page.locator("#create-theme-form #name").fill("a".repeat(256))
        page.locator("#create-theme-form #slug").fill("valid-theme")
        page.getByTestId("create-form-submit").click()

        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-theme-form .form-error[data-error='true']")).isVisible()
    }

    @Test
    fun `deep link to create opens the dialog`() {
        val tenant: Tenant = createTenant("Theme Dialog Deeplink")

        // Landing directly on ?create proves the per-entity wiring end-to-end:
        // the list ships the dialog markup (with data-create-dialog) and the shared
        // reconcile script promotes it into a real top-layer modal (`:modal`).
        gotoAndReady("/tenants/${tenant.id}/themes?create")

        page.assertNativeModalOpen("#create-theme-dialog")
    }
}
