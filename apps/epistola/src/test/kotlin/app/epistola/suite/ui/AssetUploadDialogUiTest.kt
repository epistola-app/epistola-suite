package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Asset upload dialog. The asset form's inline script (file-input preview) must
 * run when the dialog is injected; this proves the swapped-in <script> executes
 * and the drop zone is wired. Full multipart upload is covered by the handler
 * path, not the browser.
 */
class AssetUploadDialogUiTest : BasePlaywrightTest() {

    @Test
    fun `asset dialog opens and the file preview script runs`() {
        val tenant: Tenant = createTenant("Asset Dialog Test")

        page.setViewportSize(1600, 900)
        gotoAndReady("/tenants/${tenant.id}/assets")

        val dialog = page.openDialogByTrigger(
            page.getByTestId("asset-create-open"),
            "#create-asset-dialog",
        )
        assertThat(dialog).isVisible()

        assertThat(page.locator("#asset-upload-zone")).isVisible()
        val preview = page.locator("#create-asset-dialog #file-preview")
        assertThat(preview).isHidden()

        // Setting a file fires the injected change handler, which reveals the preview.
        page.locator("#create-asset-dialog #asset-file-input").setInputFiles(
            com.microsoft.playwright.options.FilePayload(
                "logo.png",
                "image/png",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            ),
        )
        assertThat(preview).isVisible()
        assertThat(page.locator("#create-asset-dialog #file-name")).hasText("logo.png")
    }

    @Test
    fun `deep link to upload opens the dialog`() {
        val tenant: Tenant = createTenant("Asset Upload Deeplink")

        // Upload dialogs are URL-addressable via `?upload` (not `?create`).
        // Landing directly on it must promote the dialog into a real top-layer
        // modal (`:modal`), proving the shared reconcile honours data-dialog-param.
        gotoAndReady("/tenants/${tenant.id}/assets?upload")

        page.assertNativeModalOpen("#create-asset-dialog")
    }
}
