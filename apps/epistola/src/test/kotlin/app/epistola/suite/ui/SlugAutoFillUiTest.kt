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

        gotoAndReady("/tenants/${tenant.id}/templates/new")
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

        // Use the plain list page as the host, NOT /templates/new: the create form
        // is now a MODAL dialog, and an open <dialog> makes everything outside it
        // (including <main>) inert — inert inputs don't dispatch `input`, so a
        // synthetic form injected into <main> could never fire the slug-auto listener.
        // This test is about the document-level MutationObserver wiring up elements
        // added after load, which is host-page-independent; the list page keeps
        // <main> interactive.
        gotoAndReady("/tenants/${tenant.id}/templates")
        assertThat(page.locator("main")).isVisible()

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
