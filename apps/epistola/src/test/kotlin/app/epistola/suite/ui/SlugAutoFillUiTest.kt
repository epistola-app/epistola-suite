package app.epistola.suite.ui

import app.epistola.suite.tenants.Tenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

class SlugAutoFillUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = createTenant("Slug AutoFill Test")

    @Test
    fun `slug field should reject invalid characters`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/templates")
        page.openDialogByTrigger(page.getByTestId("template-create-open-empty"), "#create-template-dialog")
        assertThat(page.locator("#slug")).isVisible()

        val slugInput = page.locator("#slug")
        slugInput.click()
        page.keyboard().press("Control+A")
        page.keyboard().type("HELLO_WORLD!")

        // Web-first: retries until the synchronous slug filter has applied.
        assertThat(slugInput).hasValue(Pattern.compile("^[a-z0-9-]*$"))
    }

    @Test
    fun `slug auto-fill should work for elements added after page load`() {
        val tenant = createTestTenant()

        // The MutationObserver wiring is global (document.body subtree), so any
        // list page works — no dedicated /new page needed.
        gotoAndReady("/tenants/${tenant.id}/templates")

        page.evaluate(
            """
            () => {
                var div = document.createElement('div');
                div.innerHTML =
                    '<div class="card create-form-card"><div class="card-content">' +
                    '<input type="text" id="dynamic-name" name="name" class="ep-input"' +
                    ' placeholder="Dynamic Name" data-slug-source="dynamic-slug">' +
                    '<input type="text" id="dynamic-slug" name="slug" class="ep-input"' +
                    ' placeholder="dynamic-slug">' +
                    '</div></div>';
                document.querySelector('main').appendChild(div);
            }
        """,
        )

        assertThat(page.locator("#dynamic-name")).isVisible()
        assertThat(page.locator("#dynamic-slug")).isVisible()

        page.locator("#dynamic-name").fill("Hello World")

        assertThat(page.locator("#dynamic-slug")).hasValue("hello-world")
    }
}
