package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Font upload dialog. The font form's inline script (the "+ Add face" row
 * cloning) must run when the dialog is injected into #dialog-host — this proves
 * htmx executes the swapped-in <script>. Full multipart upload is covered by the
 * handler path, not the browser.
 */
class FontUploadDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `font dialog opens and the add-face script runs`() {
        val tenant: Tenant = createTenant("Font Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/fonts")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("font-create-open"),
            "#create-font-dialog",
        )
        assertThat(dialog).isVisible()

        val faceRows = page.locator("#create-font-dialog .font-face-row")
        assertThat(faceRows).hasCount(1)

        // The injected script binds this button; clicking it clones a face row.
        page.locator("#create-font-dialog #add-face-btn").click()
        assertThat(faceRows).hasCount(2)
    }
}
