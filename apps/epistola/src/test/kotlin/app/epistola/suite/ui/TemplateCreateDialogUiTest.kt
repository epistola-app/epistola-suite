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
        assertThat(page.locator("#create-template-form .form-error[data-error='true']")).isVisible()
    }

    @Test
    fun `deep link to create opens the dialog`() {
        val tenant: Tenant = createTenant("Template Dialog Deeplink")

        // Landing directly on ?create (fresh tab, bookmark, refresh): the list
        // ships the dialog closed and the reconcile script opens it on load.
        gotoAndReady("/tenants/${tenant.id}/templates?create")

        // Assert it's a real top-layer modal (showModal()), not just server-rendered
        // <dialog> markup — `:modal` only matches a top-layer dialog, so this proves
        // the reconcile script actually promoted it, not that the HTML merely exists.
        page.assertNativeModalOpen("#create-template-dialog")
    }

    @Test
    fun `opening the dialog pushes create onto the URL`() {
        val tenant: Tenant = createTenant("Template Dialog Pushurl")

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )

        // Server returned HX-Push-Url; the address bar now deep-links the open dialog.
        assertThat(page).hasURL(Pattern.compile(".*/templates\\?create$"))
    }

    @Test
    fun `cancel strips create from the URL and closes the dialog`() {
        val tenant: Tenant = createTenant("Template Dialog Cancel")

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )

        page.locator("#create-template-dialog [data-dialog-close]").click()

        // Closing is pure DOM, but the close handler strips ?create so the URL
        // stops lying — back to the plain list URL, and the modal layer is gone
        // (`:modal` no longer matches once close() leaves the top layer).
        page.assertNoNativeModal("#create-template-dialog")
        assertThat(page).hasURL(Pattern.compile(".*/templates$"))
    }

    @Test
    fun `back button closes the dialog`() {
        val tenant: Tenant = createTenant("Template Dialog Back")

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )
        assertThat(page).hasURL(Pattern.compile(".*/templates\\?create$"))

        // Opening pushed a history entry, so Back is the natural "close it" gesture.
        page.goBack()

        // Reconcile fires on history restore and drops it out of the top layer.
        page.assertNoNativeModal("#create-template-dialog")
        assertThat(page).hasURL(Pattern.compile(".*/templates$"))
    }

    @Test
    fun `forward brings the dialog back`() {
        val tenant: Tenant = createTenant("Template Dialog Forward")

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.openDialogByTrigger(
            page.getByTestId("template-create-open-empty"),
            "#create-template-dialog",
        )
        assertThat(page).hasURL(Pattern.compile(".*/templates\\?create$"))

        // Back closes it — the open was a *pushed* entry, not a replace.
        page.goBack()
        page.assertNoNativeModal("#create-template-dialog")
        assertThat(page).hasURL(Pattern.compile(".*/templates$"))

        // ...so Forward is "redo the open": ?create returns and reconcile
        // re-promotes the same dialog into the top layer. Consistent with how the
        // web works everywhere — this is correct behaviour, not a bug.
        page.goForward()
        page.assertNativeModalOpen("#create-template-dialog")
        assertThat(page).hasURL(Pattern.compile(".*/templates\\?create$"))
    }
}
