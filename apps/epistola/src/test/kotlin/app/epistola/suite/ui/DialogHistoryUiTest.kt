package app.epistola.suite.ui

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Regression: browser Back/Forward must not degrade a server-sent modal dialog.
 *
 * htmx's history cache serialises `<dialog open>` but NOT showModal()'s top-layer
 * state, and slug-auto's per-element listeners are not serialised either. Without
 * the `htmx:historyRestore` re-init (behaviors.js / slug-auto.js) a restored
 * dialog is open-but-non-modal (no backdrop, off-centre, ESC inert) and auto-slug
 * is dead. These assert the dialog stays a real modal and auto-slug keeps working
 * after Back→Forward. Uses the catalog create dialog as a representative case —
 * the fix is in the shared JS, so it covers every server-sent dialog.
 */
class DialogHistoryUiTest : BasePlaywrightTest() {

    private fun openCreateDialogViaHistory(tenantId: String) {
        gotoAndReady("/tenants/$tenantId/catalogs")
        page.htmxSettle()

        // Open the create dialog off the header action (keyboard, per sticky-nav).
        val trigger = page.locator("[data-testid='catalog-create-open']")
        assertThat(trigger).isVisible()
        trigger.press("Enter")
        assertThat(page.locator("dialog[open]#create-catalog-dialog")).isVisible()

        // Back → list, dialog gone; Forward → htmx restores the /new snapshot.
        page.goBack()
        page.htmxSettle()
        assertThat(page.locator("#create-catalog-dialog")).hasCount(0)
        page.goForward()
        page.htmxSettle()
        assertThat(page.locator("dialog[open]#create-catalog-dialog")).isVisible()
    }

    @Test
    fun `restored dialog is still modal and ESC closes it`() {
        val tenant = createTenant("Dialog History Modal")
        openCreateDialogViaHistory("${tenant.id}")

        // Re-promoted to a real modal (backdrop, centering, ESC-closable).
        val isModal =
            page.evaluate("() => document.querySelector('#create-catalog-dialog')?.matches(':modal')")
        Assertions.assertThat(isModal).describedAs("dialog modal after Back/Forward").isEqualTo(true)

        // ESC only closes a MODAL dialog — proves the top-layer state is back.
        page.keyboard().press("Escape")
        assertThat(page.locator("dialog[open]#create-catalog-dialog")).hasCount(0)
    }

    @Test
    fun `auto-slug still works on the restored dialog`() {
        val tenant = createTenant("Dialog History Slug")
        openCreateDialogViaHistory("${tenant.id}")

        // The name→slug wiring must be re-bound after restore: typing a name fills
        // the slug field.
        page.locator("#catalogName").fill("Hello World")
        assertThat(page.locator("#catalogSlug")).hasValue("hello-world")
    }
}
