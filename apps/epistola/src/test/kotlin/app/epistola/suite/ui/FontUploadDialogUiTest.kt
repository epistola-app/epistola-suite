package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FilePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

        // The general error is swapped in via OOB and the dialog stays open
        // (web-first assertions auto-wait for the swap).
        val generalError = page.locator("#create-font-dialog #font-error-general")
        assertThat(generalError).hasAttribute("data-error", "true")
        assertThat(generalError).containsText("unique")
        assertThat(dialog).isVisible()

        page.htmxSettle()

        // The load-bearing guarantee: OOB swapped only the error span, so BOTH chosen
        // files survived — a whole-form re-render would have wiped the file inputs.
        assertEquals(1, (faceFiles.first().evaluate("el => el.files.length") as Number).toInt())
        assertEquals(1, (faceFiles.nth(1).evaluate("el => el.files.length") as Number).toInt())

        // :has(.form-error[data-error='true']) fired: the faces group (which holds the
        // general error span) now borders its inputs differently from the clean slug input.
        val bordersDiffer = page.evaluate(
            """
            () => {
                const weight = document.querySelector("#create-font-dialog .font-face-row input[name='weight']");
                const slug = document.querySelector("#create-font-dialog #slug");
                return getComputedStyle(weight).borderColor !== getComputedStyle(slug).borderColor;
            }
            """,
        ) as Boolean
        assertTrue(bordersDiffer, "the faces group should show the error border via :has()")
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
