package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class HtmxRequestTest {

    @Test
    fun `isHtmx returns true when HX-Request header is present`() {
        val request = createRequest("HX-Request" to "true")

        assertThat(request.isHtmx).isTrue()
    }

    @Test
    fun `isHtmx returns false when HX-Request header is absent`() {
        val request = createRequest()

        assertThat(request.isHtmx).isFalse()
    }

    @Test
    fun `isHtmx returns false when HX-Request header is not true`() {
        val request = createRequest("HX-Request" to "false")

        assertThat(request.isHtmx).isFalse()
    }

    @Test
    fun `htmxTrigger returns the trigger element ID`() {
        val request = createRequest("HX-Trigger" to "my-button")

        assertThat(request.htmxTrigger).isEqualTo("my-button")
    }

    @Test
    fun `htmxTrigger returns null when header is absent`() {
        val request = createRequest()

        assertThat(request.htmxTrigger).isNull()
    }

    @Test
    fun `htmxTarget returns the target element ID`() {
        val request = createRequest("HX-Target" to "content-area")

        assertThat(request.htmxTarget).isEqualTo("content-area")
    }

    @Test
    fun `htmxCurrentUrl returns the current browser URL`() {
        val request = createRequest("HX-Current-URL" to "http://localhost:8080/page")

        assertThat(request.htmxCurrentUrl).isEqualTo("http://localhost:8080/page")
    }

    @Test
    fun `htmxBoosted returns true when HX-Boosted header is present`() {
        val request = createRequest("HX-Boosted" to "true")

        assertThat(request.htmxBoosted).isTrue()
    }

    @Test
    fun `htmxBoosted returns false when header is absent`() {
        val request = createRequest()

        assertThat(request.htmxBoosted).isFalse()
    }

    @Test
    fun `htmxHistoryRestoreRequest returns true when set`() {
        val request = createRequest("HX-History-Restore-Request" to "true")

        assertThat(request.htmxHistoryRestoreRequest).isTrue()
    }

    @Test
    fun `htmxPrompt returns the user prompt response`() {
        val request = createRequest("HX-Prompt" to "user input")

        assertThat(request.htmxPrompt).isEqualTo("user input")
    }

    // -- listParam: list-view filter/sort param with HX-Current-URL fallback ---------------

    @Test
    fun `listParam uses the request's own param when present`() {
        val request = request(params = mapOf("q" to "invoice"))

        assertThat(request.listParam("q")).isEqualTo("invoice")
    }

    @Test
    fun `listParam treats a present-but-blank param as cleared and does NOT fall back`() {
        // A deliberately-cleared search must stay cleared even though the previous browser
        // URL still carries the old term — otherwise clearing the box would resurrect it.
        val request = request(
            params = mapOf("q" to ""),
            headers = mapOf("HX-Current-URL" to "http://localhost:8080/tenants/t/catalogs?q=stale"),
        )

        assertThat(request.listParam("q")).isNull()
    }

    @Test
    fun `listParam falls back to HX-Current-URL when the param is absent from the request`() {
        // A mutation (delete/release) POSTs to a fixed URL with no list state; the active
        // filter is recovered from the browser URL htmx sends along.
        val request = request(
            headers = mapOf("HX-Current-URL" to "http://localhost:8080/tenants/t/catalogs?q=invoice&sort=name"),
        )

        assertThat(request.listParam("q")).isEqualTo("invoice")
        assertThat(request.listParam("sort")).isEqualTo("name")
    }

    @Test
    fun `listParam url-decodes the value recovered from HX-Current-URL`() {
        val request = request(
            headers = mapOf("HX-Current-URL" to "http://localhost:8080/tenants/t/catalogs?q=open%20sans"),
        )

        assertThat(request.listParam("q")).isEqualTo("open sans")
    }

    @Test
    fun `listParam returns null when the param is absent everywhere`() {
        assertThat(request().listParam("q")).isNull()
        assertThat(
            request(headers = mapOf("HX-Current-URL" to "http://localhost:8080/tenants/t/catalogs?sort=name"))
                .listParam("q"),
        ).isNull()
    }

    private fun createRequest(vararg headers: Pair<String, String>): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        headers.forEach { (name, value) ->
            mockRequest.addHeader(name, value)
        }
        return ServerRequest.create(mockRequest, emptyList())
    }

    private fun request(
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        params.forEach { (name, value) -> mockRequest.setParameter(name, value) }
        headers.forEach { (name, value) -> mockRequest.addHeader(name, value) }
        return ServerRequest.create(mockRequest, emptyList())
    }
}
