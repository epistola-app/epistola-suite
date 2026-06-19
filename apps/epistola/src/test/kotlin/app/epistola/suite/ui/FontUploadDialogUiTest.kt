package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FilePayload
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `a validation error renders inline via OOB and keeps the chosen face files`() {
        val tenant: Tenant = createTenant("Font Upload Error")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/fonts")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("font-create-open"),
            "#create-font-dialog",
        )
        assertThat(dialog).isVisible()

        // All client-side-valid fields, but two faces with the SAME (weight, italic).
        // That's a server-only error (the browser can't catch it), so the submit
        // actually reaches the handler — unlike a bad slug, which `pattern`/`required`
        // would block client-side.
        page.locator("#create-font-dialog #name").fill("Acme")
        page.locator("#create-font-dialog #slug").fill("acme-sans")
        val faceFiles = page.locator("#create-font-dialog .font-face-row input[name='file']")
        faceFiles.first().setInputFiles(FilePayload("acme-regular.ttf", "font/ttf", byteArrayOf(0x01, 0x02, 0x03)))
        page.locator("#create-font-dialog #add-face-btn").click()
        assertThat(faceFiles).hasCount(2)
        faceFiles.nth(1).setInputFiles(FilePayload("acme-dupe.ttf", "font/ttf", byteArrayOf(0x04, 0x05, 0x06)))

        page.locator("#create-font-dialog [data-testid='create-form-submit']").click()

        // The duplicate-face error is general (not field-tied), so it is thrown and
        // rendered by the shared filter as an OOB swap into the dialog's #dialog-error
        // region — inside the still-open modal (web-first assertions auto-wait).
        assertThat(page.getByTestId("dialog-error")).containsText("Each (weight, italic) face must be unique")
        assertThat(dialog).isVisible()

        page.htmxSettle()

        // The load-bearing guarantee: the OOB swap (HX-Reswap:none) never touched the
        // form, so BOTH chosen files survived — a whole-form re-render would have
        // wiped the file inputs.
        assertEquals(1, (faceFiles.first().evaluate("el => el.files.length") as Number).toInt())
        assertEquals(1, (faceFiles.nth(1).evaluate("el => el.files.length") as Number).toInt())
    }

    @Test
    fun `a server error renders in the dialog's general error region, not behind the modal`() {
        val tenant: Tenant = createTenant("Font Server Error")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/fonts")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("font-create-open"),
            "#create-font-dialog",
        )
        assertThat(dialog).isVisible()

        // Valid required fields, but no face file — a general (non-field) error the
        // handler throws. The shared filter must render it in the dialog's #dialog-error
        // region (an OOB swap), keeping it inside the open modal rather than behind it.
        page.locator("#create-font-dialog #name").fill("Acme")
        page.locator("#create-font-dialog #slug").fill("acme-sans")
        page.locator("#create-font-dialog [data-testid='create-form-submit']").click()

        val region = page.getByTestId("dialog-error")
        assertThat(region).containsText("At least one face file is required")
        assertThat(dialog).isVisible()
    }

    @Test
    fun `deep link to upload opens the dialog`() {
        val tenant: Tenant = createTenant("Font Upload Deeplink")

        // Upload dialogs are URL-addressable via `?upload` (not `?create`).
        // Landing directly on it must promote the dialog into a real top-layer
        // modal (`:modal`), proving the shared reconcile honours data-dialog-param.
        gotoAndReady("/tenants/${tenant.id}/fonts?upload")

        page.assertNativeModalOpen("#create-font-dialog")
    }
}
