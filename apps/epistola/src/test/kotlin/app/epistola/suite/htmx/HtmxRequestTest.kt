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

    private fun createRequest(vararg headers: Pair<String, String>): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        headers.forEach { (name, value) ->
            mockRequest.addHeader(name, value)
        }
        return ServerRequest.create(mockRequest, emptyList())
    }
}
