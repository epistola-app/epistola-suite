// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

/**
 * Unit coverage for the form-binding domain-ID validators. Focuses on the
 * `asThemeId()` field validator (added for the theme create dialog) alongside its
 * `asTemplateId()` sibling, so both share identical failure behavior.
 */
class FormBinderTest {

    @Test
    fun `asThemeId accepts a valid slug`() {
        val form = requestWith("slug" to "corporate").form {
            field("slug") { asThemeId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asThemeId rejects an invalid slug with the theme id error`() {
        val form = requestWith("slug" to "Invalid Theme").form {
            field("slug") { asThemeId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid theme ID format")
    }

    @Test
    fun `asAttributeId accepts a valid slug`() {
        val form = requestWith("slug" to "language").form {
            field("slug") { asAttributeId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asAttributeId rejects an invalid slug with the attribute id error`() {
        val form = requestWith("slug" to "Invalid Attribute").form {
            field("slug") { asAttributeId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid attribute ID format")
    }

    @Test
    fun `asCatalogId accepts a valid slug`() {
        val form = requestWith("slug" to "my-templates").form {
            field("slug") { asCatalogId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asCatalogId rejects an invalid slug with the catalog id error`() {
        val form = requestWith("slug" to "BAD SLUG").form {
            field("slug") { asCatalogId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid catalog ID format")
    }

    @Test
    fun `asCodeListId accepts a valid slug`() {
        val form = requestWith("slug" to "languages").form {
            field("slug") { asCodeListId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asCodeListId rejects an invalid slug with the code-list id error`() {
        val form = requestWith("slug" to "BAD SLUG").form {
            field("slug") { asCodeListId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid code-list ID format")
    }

    @Test
    fun `asStencilId accepts a valid slug`() {
        val form = requestWith("slug" to "corporate-header").form {
            field("slug") { asStencilId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asStencilId rejects an invalid slug with the stencil id error`() {
        val form = requestWith("slug" to "Invalid Stencil").form {
            field("slug") { asStencilId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid stencil ID format")
    }

    @Test
    fun `asTemplateId accepts a valid slug`() {
        val form = requestWith("slug" to "monthly-invoice").form {
            field("slug") { asTemplateId() }
        }

        assertThat(form.hasErrors()).isFalse()
    }

    @Test
    fun `asTemplateId rejects an invalid slug with the template id error`() {
        val form = requestWith("slug" to "Invalid Template").form {
            field("slug") { asTemplateId() }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid template ID format")
    }

    // ── Interaction with the pattern/length rules the handlers declare ──────────
    //
    // The create dialogs stack asXxxId() ON TOP of pattern()/minLength()/maxLength()
    // that restate the very same constraints the key enforces (EntityKey.kt). The
    // domain-ID check must therefore never clobber the specific message the field
    // rules already produced — it is a fallback, not an override. The one thing it
    // legitimately adds (reserved words) is asserted last.

    @Test
    fun `a minLength failure keeps its specific message and is not replaced by the id error`() {
        // Mirrors ThemeHandler.create: pattern + minLength(3) + maxLength(20) + asThemeId().
        val form = requestWith("slug" to "ab").form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
                asThemeId()
            }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Slug must be at least 3 characters")
    }

    @Test
    fun `a maxLength failure keeps its specific message and is not replaced by the id error`() {
        val form = requestWith("slug" to "a".repeat(25)).form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
                asThemeId()
            }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Slug must not exceed 20 characters")
    }

    @Test
    fun `a required failure on a blank value is never replaced by the id error`() {
        val form = requestWith("slug" to "  ").form {
            field("slug") {
                required()
                asThemeId()
            }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Slug is required")
    }

    @Test
    fun `the id validator still catches what the field rules cannot - a reserved word`() {
        // "new" passes pattern, minLength(3) and maxLength(50); only TemplateKey's
        // RESERVED_WORDS rejects it. This is the case asTemplateId() exists for, and
        // it is reached precisely because no field rule failed first.
        val form = requestWith("slug" to "new").form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(50)
                asTemplateId()
            }
        }

        assertThat(form.hasErrors()).isTrue()
        assertThat(form.errors["slug"]).isEqualTo("Invalid template ID format")
    }

    private fun requestWith(vararg params: Pair<String, String>): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        params.forEach { (name, value) -> mockRequest.addParameter(name, value) }
        return ServerRequest.create(mockRequest, emptyList())
    }
}
