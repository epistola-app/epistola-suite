package app.epistola.suite.config

import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.common.ids.CatalogKey
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

@Tag("unit")
class UiExceptionFilterTest {
    private val objectMapper = JsonMapper.builder().build()
    private val filter = UiExceptionFilter(objectMapper)

    private fun runWith(accept: String?, isHtmx: Boolean, ex: Exception): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/tenants/t/themes")
        if (accept != null) request.addHeader("Accept", accept)
        if (isHtmx) request.addHeader("HX-Request", "true")
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, _ -> throw ex })
        return response
    }

    @Test
    fun `JSON Accept branch emits valid Jackson-encoded JSON`() {
        // The domain message contains apostrophes and a period — the body must
        // be structurally valid JSON produced by a real encoder, not a
        // hand-built string.
        val response = runWith(
            MediaType.APPLICATION_JSON_VALUE,
            isHtmx = false,
            CatalogReadOnlyException(CatalogKey("system")),
        )

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("error").asString())
            .isEqualTo("Cannot modify resources in read-only catalog 'system'. Subscribed catalogs are read-only.")
    }

    @Test
    fun `HTMX branch also emits valid JSON for the generic 500 path`() {
        val response = runWith(accept = null, isHtmx = true, IllegalStateException("internal boom"))

        assertThat(response.status).isEqualTo(500)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        // Unknown exceptions are intentionally opaque to the client.
        assertThat(parsed.get("error").asString()).isEqualTo("An unexpected error occurred.")
    }

    @Test
    fun `non-JSON non-HTMX requests use container sendError, not the JSON body`() {
        val response = runWith(accept = "text/html", isHtmx = false, CatalogReadOnlyException(CatalogKey("system")))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.errorMessage)
            .isEqualTo("Cannot modify resources in read-only catalog 'system'. Subscribed catalogs are read-only.")
        assertThat(response.contentAsString).isEmpty()
    }
}
