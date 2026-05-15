package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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
        // The popover content (header + "New" button) is injected by an `hx-trigger="load"`
        // request fired during FAB init. Wait for that fetch to settle before any selector
        // check so we don't race the script execution that creates `#feedback-fab` itself.
        page.waitForLoadState(LoadState.NETWORKIDLE)
        page.waitForSelector(
            "#feedback-fab",
            Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(SELECTOR_TIMEOUT_MS),
        )
        page.click("#feedback-fab")
        page.waitForSelector(".feedback-popover--open")
        page.waitForSelector(
            ".feedback-popover-header button",
            Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.VISIBLE)
                .setTimeout(SELECTOR_TIMEOUT_MS),
        )
        page.click(".feedback-popover-header button")
        page.waitForSelector("#feedback-fab-dialog[open]")
    }

    companion object {
        private const val SELECTOR_TIMEOUT_MS = 15_000.0
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
        @Disabled(
            "Flaky on CI — waitForSelector(\".feedback-popover--open\") times out because the " +
                "popover has display:block + width:340 but no min-height; its content is fetched " +
                "async via HTMX hx-trigger=load, so until the response arrives the bounding box " +
                "is height 0 and Playwright considers the element hidden. See issue #418.",
        )
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
