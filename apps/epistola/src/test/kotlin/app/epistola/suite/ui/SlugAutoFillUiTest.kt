package app.epistola.suite.ui

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SlugAutoFillUiTest : BasePlaywrightTest() {

    private fun createTestTenant(): Tenant = withMediator {
        CreateTenant(
            id = TenantKey.of("slugtest-${System.nanoTime()}"),
            name = "Slug AutoFill Test",
        ).execute()
    }

    @Test
    fun `slug field should reject invalid characters`() {
        val tenant = createTestTenant()

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/new")
        page.waitForSelector("#slug")

        val slugInput = page.locator("#slug")
        slugInput.click()
        page.keyboard().press("Control+A")
        page.keyboard().type("HELLO_WORLD!")

        val slugValue = slugInput.inputValue()

        assertTrue(
            slugValue.matches(Regex("^[a-z0-9-]*$")),
            "Expected slug to contain only [a-z0-9-] but got: '$slugValue'",
        )
    }

    @Test
    fun `slug auto-fill should work for elements added after page load`() {
        val tenant = createTestTenant()

        page.navigate("${baseUrl()}/tenants/${tenant.id}/templates/new")
        page.waitForSelector("#slug")

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

        val slugValue = page.locator("#dynamic-slug").inputValue()

        assertTrue(
            slugValue.isNotEmpty(),
            "Expected slug to be auto-filled but got empty value",
        )
        assertEquals(
            "hello-world",
            slugValue,
            "Expected slug to be 'hello-world' but got: '$slugValue'",
        )
    }
}
