// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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

    /**
     * Themes are the worst ratio in the app: the name field allows 100 characters and the slug
     * field 20. `maxlength` constrains typing and pasting but never a value a script assigns, so
     * the derived slug used to land at five times its own limit — and the user then had to correct
     * a field they had not filled in.
     */
    @Test
    fun `a derived slug is truncated to the slug field's own maxlength`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/themes/new")
        val slug = page.locator("#slug")
        assertThat(slug).isVisible()

        page.locator("#name").fill("Gemeentelijke huisstijl voor bezwaarschriften en aanslagen")

        assertThat(slug).hasValue(Pattern.compile("^[a-z0-9-]{1,20}$"))
        // Truncation must not leave a trailing hyphen behind, which is not a valid slug.
        assertThat(slug).not().hasValue(Pattern.compile("-$"))
    }

    /**
     * The trap in truncating, and why it needs a test of its own: it does not bite while typing
     * into an empty form, but at wire-up. A form that arrives already populated — the re-render
     * after a validation error, for instance — carries a long name and its *truncated* slug.
     * `slugManuallyEdited` is decided by comparing the field against what it should hold, so
     * comparing against the untruncated derivation makes those two disagree, and auto-fill
     * switches itself off on a slug nobody touched.
     *
     * Built by injection rather than through a real form, because reaching that state through the
     * UI means submitting an invalid form first, which exercises the error path rather than this.
     */
    @Test
    fun `a form that arrives with an already-truncated slug keeps auto-filling`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/templates")
        assertThat(page.locator("main")).isVisible()

        page.evaluate(
            PREFILLED_FORM,
        )

        assertThat(page.locator("#prefilled-slug")).hasValue("gemeentelijke-huisst")

        // Nobody edited the slug, so changing the name must still drive it.
        page.locator("#prefilled-name").fill("Aanslagen")

        assertThat(page.locator("#prefilled-slug")).hasValue("aanslagen")
    }

    /** The other half: a slug the user really did type stays put. */
    @Test
    fun `auto-fill keeps working after truncation, and still stops once the slug is edited by hand`() {
        val tenant = createTestTenant()

        gotoAndReady("/tenants/${tenant.id}/themes/new")
        val name = page.locator("#name")
        val slug = page.locator("#slug")

        name.fill("Gemeentelijke huisstijl voor bezwaarschriften")
        val afterTruncation = slug.inputValue()

        // Still following the name: the truncated value did not read as a manual edit.
        name.fill("Andere huisstijl voor aanslagen en beschikkingen")
        assertThat(slug).not().hasValue(afterTruncation)
        assertThat(slug).hasValue(Pattern.compile("^andere"))

        // And a real hand edit still stops it. fill() replaces the value and fires `input`, which
        // is what marks the slug as manually edited; typing cannot be used here because the field
        // is already at its 20-character maxlength, so the browser would swallow the keystrokes.
        slug.fill("eigen-slug")
        name.fill("Weer een andere naam")
        assertThat(slug).hasValue("eigen-slug")
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

    private companion object {
        /**
         * A name field holding more than its slug field allows, paired with the slug already
         * capped at the 20 characters themes permit. Attributes are single-quoted so the
         * markup needs no escaping inside the Kotlin string.
         */
        const val PREFILLED_FORM =
            "() => {" +
                "  const host = document.querySelector('main');" +
                "  const name = document.createElement('input');" +
                "  name.id = 'prefilled-name';" +
                "  name.value = 'Gemeentelijke huisstijl voor bezwaarschriften';" +
                "  name.setAttribute('data-slug-source', 'prefilled-slug');" +
                "  const slug = document.createElement('input');" +
                "  slug.id = 'prefilled-slug';" +
                "  slug.maxLength = 20;" +
                "  slug.value = 'gemeentelijke-huisst';" +
                "  host.appendChild(name);" +
                "  host.appendChild(slug);" +
                "}"
    }
}
