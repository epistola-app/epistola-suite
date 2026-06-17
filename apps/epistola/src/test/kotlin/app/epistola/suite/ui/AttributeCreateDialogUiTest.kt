package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Attribute create dialog. Exercises the conditional-pane script running inside
 * the injected dialog, a successful create (HX-Redirect to the list), and a
 * server-side validation error keeping the dialog open.
 */
class AttributeCreateDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `create attribute via dialog redirects to the list`() {
        val tenant: Tenant = createTenant("Attribute Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/attributes")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("attribute-create-open"),
            "#create-attribute-dialog",
        )
        assertThat(dialog).isVisible()

        page.locator("#create-attribute-dialog #displayName").fill("Language")
        assertThat(page.locator("#create-attribute-dialog #slug")).hasValue("language")

        // The injected constraint-kind script toggles panes: the inline pane is
        // hidden until its radio is chosen.
        val inlinePane = page.locator("#create-attribute-dialog [data-constraint-pane='inline']")
        assertThat(inlinePane).isHidden()
        page.locator("#create-attribute-dialog input[name='constraintKind'][value='inline']").check()
        assertThat(inlinePane).isVisible()

        page.getByTestId("create-form-submit").click()

        assertThat(page).hasURL(Pattern.compile(".*/attributes$"))
    }

    @Test
    fun `validation error keeps the dialog open with field errors`() {
        val tenant: Tenant = createTenant("Attribute Dialog Errors")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/attributes")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("attribute-create-open"),
            "#create-attribute-dialog",
        )

        // displayName caps at 100 server-side with no HTML5 maxlength; a clean
        // slug isolates the failure to the display name.
        page.locator("#create-attribute-dialog #displayName").fill("a".repeat(256))
        page.locator("#create-attribute-dialog #slug").fill("valid-attr")
        page.getByTestId("create-form-submit").click()

        assertThat(dialog).isVisible()
        assertThat(page.locator("#create-attribute-form .form-error")).isVisible()
    }

    @Test
    fun `deep link to create opens the dialog`() {
        val tenant: Tenant = createTenant("Attribute Dialog Deeplink")

        // Landing directly on ?create proves the per-entity wiring end-to-end: the
        // list ships the dialog markup (with data-create-dialog) and the shared
        // reconcile script promotes it into a real top-layer modal (`:modal`).
        gotoAndReady("/tenants/${tenant.id}/attributes?create")

        page.assertNativeModalOpen("#create-attribute-dialog")
    }
}
