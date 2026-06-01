package app.epistola.suite.config

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

@Tag("unit")
class HxBoostRetargetFilterTest {
    private val filter = HxBoostRetargetFilter()

    private fun runWith(boosted: Boolean, htmx: Boolean): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/tenants/t/api-keys")
        if (htmx) request.addHeader("HX-Request", "true")
        if (boosted) request.addHeader("HX-Boosted", "true")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> })
        return response
    }

    @Test
    fun `boosted navigation is forced to replace the body`() {
        // A boosted link inside a form with hx-target="#form-area" would otherwise
        // inherit that target and nest a full page into the form div. The filter
        // overrides it back to a body swap regardless of the inherited target.
        val response = runWith(boosted = true, htmx = true)

        assertThat(response.getHeader("HX-Retarget")).isEqualTo("body")
        assertThat(response.getHeader("HX-Reswap")).isEqualTo("innerHTML")
    }

    @Test
    fun `non-boosted HTMX requests are left untouched`() {
        // Explicit hx-post/hx-get requests (inline-error forms, cascading dropdowns)
        // are NOT boosted and must keep their own hx-target/hx-swap. The filter must
        // not stamp a body retarget onto their fragment responses.
        val response = runWith(boosted = false, htmx = true)

        assertThat(response.getHeader("HX-Retarget")).isNull()
        assertThat(response.getHeader("HX-Reswap")).isNull()
    }

    @Test
    fun `plain non-HTMX requests are left untouched`() {
        val response = runWith(boosted = false, htmx = false)

        assertThat(response.getHeader("HX-Retarget")).isNull()
        assertThat(response.getHeader("HX-Reswap")).isNull()
    }
}
