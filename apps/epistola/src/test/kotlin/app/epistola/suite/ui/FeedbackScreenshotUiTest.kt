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

    private fun openFeedbackDialog() {
        page.navigate("${baseUrl()}/tenants/${tenant.id}")
        page.waitForSelector("#feedback-fab")
        page.click("#feedback-fab")
        page.waitForSelector(".feedback-popover--open")
        page.click(".feedback-popover-header button")
        page.waitForSelector("#feedback-fab-dialog[open]")
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

            page.click("#fb-capture-region")

            page.waitForSelector(".fb-capture-overlay")
            assertThat(page.locator(".fb-capture-overlay")).isVisible()
            assertThat(page.locator(".fb-capture-hint")).isVisible()
            assertThat(page.locator(".fb-capture-hint")).containsText("Click and drag")
        }

        @Test
        fun `escape key cancels region selection and restores dialog`() {
            openFeedbackDialog()

            page.click("#fb-capture-region")
            page.waitForSelector(".fb-capture-overlay")

            page.keyboard().press("Escape")

            assertThat(page.locator(".fb-capture-overlay")).hasCount(0)
            page.waitForSelector("#feedback-fab-dialog[open]")
        }

        @Test
        fun `small selection is treated as cancellation`() {
            openFeedbackDialog()

            page.click("#fb-capture-region")
            page.waitForSelector(".fb-capture-overlay")

            val overlay = page.locator(".fb-capture-overlay")
            overlay.dispatchEvent("mousedown", mapOf("clientX" to 100, "clientY" to 100))
            overlay.dispatchEvent("mouseup", mapOf("clientX" to 105, "clientY" to 105))

            page.waitForSelector("#feedback-fab-dialog[open]")
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
