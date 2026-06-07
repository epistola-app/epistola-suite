package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FeedbackScreenshotUiTest : BasePlaywrightTest() {

    private lateinit var tenant: Tenant

    @BeforeEach
    fun setUp() {
        tenant =
            withMediator {
                CreateTenant(
                    id = TenantKey.of("test-fb-${System.nanoTime()}"),
                    name = "Feedback Test Tenant",
                ).execute()
            }

        // Mock getDisplayMedia before any page JS runs so isSupported evaluates to true.
        // Headless Chromium doesn't expose getDisplayMedia.
        page.addInitScript(
            """
            if (!navigator.mediaDevices) navigator.mediaDevices = {};
            navigator.mediaDevices.getDisplayMedia = async () => {
                throw new DOMException('Test environment', 'NotAllowedError');
            };
            """,
        )
    }

    /**
     * Opens the feedback dialog deterministically.
     *
     * The FAB + popover are injected by `feedback-fab.js`; the popover content
     * (header + "New" button) is fetched by an `hx-trigger="load"` request, and
     * the popover itself is a class-toggled div with no min-height — so until
     * that fetch settles its box is height 0 (the #418-B flake). We therefore
     * wait for the FAB, let the htmx load settle, open the popover, then open
     * the native `<dialog>` via [openDialogByTrigger] (which waits for it to be
     * `:modal` AND non-zero) and let the dialog's own submit-form load settle.
     */
    private fun openFeedbackDialog() {
        gotoAndReady("/tenants/${tenant.id}")
        assertThat(page.locator("#feedback-fab")).isVisible()
        page.htmxSettle()
        page.locator("#feedback-fab").click()
        page.openDialogByTrigger(
            page.locator(".feedback-popover-header button"),
            "#feedback-fab-dialog",
        )
        // The submit form is loaded into the dialog via htmx; wait for it so
        // the dialog body (#fb-capture-region etc.) is present and rendered.
        page.htmxSettle()
    }

    /**
     * Opens the submit-feedback dialog from the **feedback list page** (`#feedback-dialog`),
     * which is a different host dialog than the FAB's (`#feedback-fab-dialog`). The "New
     * Feedback" button both `showModal()`s the dialog and htmx-loads the submit form into it.
     */
    private fun openFeedbackDialogFromList() {
        gotoAndReady("/tenants/${tenant.id}/feedback")
        // The "New Feedback" button sits at the top of the page under the sticky nav, which
        // intercepts a real pointer click. Dispatch the click event directly (it both
        // showModal()s #feedback-dialog and htmx-loads the submit form) — the same
        // dispatchEvent pattern used for the overlay below.
        page.locator(".page-actions button.ep-btn-primary").dispatchEvent("click")
        page.htmxSettle()
        // Web-first: the dialog is open and the submit form has rendered.
        assertThat(page.locator("#fb-capture-region")).isVisible()
    }

    @Nested
    inner class ScreenshotButtons {

        @Test
        fun `feedback form shows capture region and capture visible buttons`() {
            openFeedbackDialog()

            assertThat(page.locator("#fb-capture-region")).isVisible()
            assertThat(page.locator("#fb-capture-viewport")).isVisible()
            assertThat(page.locator("#fb-screenshot-preview")).isHidden()
        }

        @Test
        fun `screenshot preview is hidden by default`() {
            openFeedbackDialog()

            assertThat(page.locator("#fb-screenshot-preview")).isHidden()
            assertThat(page.locator("#fb-screenshot-actions")).isVisible()
        }
    }

    @Nested
    inner class RegionSelectionOverlay {

        @Test
        fun `capture region button shows selection overlay`() {
            openFeedbackDialog()

            page.locator("#fb-capture-region").click()

            assertThat(page.locator(".fb-capture-overlay")).isVisible()
            assertThat(page.locator(".fb-capture-hint")).isVisible()
            assertThat(page.locator(".fb-capture-hint")).containsText("Click and drag")
        }

        @Test
        fun `escape key cancels region selection and restores dialog`() {
            openFeedbackDialog()

            page.locator("#fb-capture-region").click()
            assertThat(page.locator(".fb-capture-overlay")).isVisible()

            page.keyboard().press("Escape")

            assertThat(page.locator(".fb-capture-overlay")).hasCount(0)
            assertThat(page.locator("#feedback-fab-dialog")).isVisible()
        }

        @Test
        fun `small selection is treated as cancellation`() {
            openFeedbackDialog()

            page.locator("#fb-capture-region").click()
            assertThat(page.locator(".fb-capture-overlay")).isVisible()

            val overlay = page.locator(".fb-capture-overlay")
            overlay.dispatchEvent("mousedown", mapOf("clientX" to 100, "clientY" to 100))
            overlay.dispatchEvent("mouseup", mapOf("clientX" to 105, "clientY" to 105))

            // A sub-threshold drag is a cancel → the dialog is restored.
            assertThat(page.locator("#feedback-fab-dialog")).isVisible()
        }
    }

    @Nested
    inner class ListPageDialog {

        /**
         * Regression: starting a region capture from the list-page dialog must hide that
         * dialog (so the user can select the page beneath). Previously the capture script
         * hardcoded `#feedback-fab-dialog`, so when opened from the list page the
         * `#feedback-dialog` overlay stayed on screen during selection.
         */
        @Test
        fun `capture region hides the list-page submit dialog`() {
            openFeedbackDialogFromList()
            assertThat(page.locator("#fb-capture-region")).isVisible()

            page.locator("#fb-capture-region").click()

            assertThat(page.locator(".fb-capture-overlay")).isVisible()
            assertThat(page.locator("#feedback-dialog")).isHidden()

            // Escape cancels and restores the list-page dialog.
            page.keyboard().press("Escape")
            assertThat(page.locator(".fb-capture-overlay")).hasCount(0)
            assertThat(page.locator("#feedback-dialog")).isVisible()
        }
    }

    @Nested
    inner class DialogHeader {

        @Test
        fun `close button is positioned to the right of the title`() {
            openFeedbackDialog()

            val header = page.locator(".ep-dialog-header")
            val title = header.locator("h2")
            val closeButton = header.locator("button")

            // Web-first gates the non-retrying boundingBox() reads below: the
            // dialog is open and rendered (non-zero) by the time these pass.
            assertThat(title).isVisible()
            assertThat(closeButton).isVisible()

            val titleBox = title.boundingBox()
            val buttonBox = closeButton.boundingBox()
            assert(buttonBox.x > titleBox.x + titleBox.width) {
                "Close button should be positioned to the right of the title"
            }
        }
    }
}
