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
    fun `application json Accept returns an RFC 9457 problem body`() {
        val response = runWith(
            MediaType.APPLICATION_JSON_VALUE,
            isHtmx = false,
            CatalogReadOnlyException(CatalogKey("system")),
        )

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("type").asString()).isEqualTo("https://epistola.app/errors/catalog-read-only")
        assertThat(parsed.get("status").asInt()).isEqualTo(403)
        assertThat(parsed.get("detail").asString())
            .isEqualTo("Cannot modify resources in read-only catalog 'system'. Subscribed catalogs are read-only.")
        assertThat(parsed.has("code")).isFalse()
    }

    @Test
    fun `application problem+json Accept returns an RFC 9457 problem body`() {
        val response = runWith(
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            isHtmx = false,
            CatalogReadOnlyException(CatalogKey("system")),
        )

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("type").asString()).isEqualTo("https://epistola.app/errors/catalog-read-only")
    }

    @Test
    fun `HTMX request returns a problem body too`() {
        val response = runWith(accept = null, isHtmx = true, CatalogReadOnlyException(CatalogKey("system")))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("type").asString()).isEqualTo("https://epistola.app/errors/catalog-read-only")
    }

    @Test
    fun `unknown exceptions stay opaque with the internal-error type`() {
        val response = runWith(
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            isHtmx = false,
            IllegalStateException("internal boom"),
        )

        assertThat(response.status).isEqualTo(500)
        assertThat(response.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        val parsed = objectMapper.readTree(response.contentAsString)
        assertThat(parsed.get("type").asString()).isEqualTo("https://epistola.app/errors/internal-error")
        assertThat(parsed.get("detail").asString()).isEqualTo("An unexpected error occurred.")
        assertThat(parsed.get("detail").asString()).doesNotContain("boom")
    }

    @Test
    fun `HTML navigations use container sendError, not a JSON body`() {
        val response = runWith(accept = "text/html", isHtmx = false, CatalogReadOnlyException(CatalogKey("system")))

        assertThat(response.status).isEqualTo(403)
        assertThat(response.errorMessage)
            .isEqualTo("Cannot modify resources in read-only catalog 'system'. Subscribed catalogs are read-only.")
        assertThat(response.contentAsString).isEmpty()
    }
}
