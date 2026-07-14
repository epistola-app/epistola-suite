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

    private fun requestWith(vararg params: Pair<String, String>): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        params.forEach { (name, value) -> mockRequest.addParameter(name, value) }
        return ServerRequest.create(mockRequest, emptyList())
    }
}
