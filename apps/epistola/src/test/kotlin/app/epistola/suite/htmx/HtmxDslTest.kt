package app.epistola.suite.htmx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.servlet.function.ServerRequest

class HtmxDslTest {

    @Nested
    inner class ModelBuilderTest {
        @Test
        fun `builds empty model when no values added`() {
            val model = ModelBuilder().build()
            assertThat(model).isEmpty()
        }

        @Test
        fun `builds model with single value`() {
            val model = ModelBuilder().apply {
                "key" to "value"
            }.build()

            assertThat(model).containsEntry("key", "value")
        }

        @Test
        fun `builds model with multiple values`() {
            val model = ModelBuilder().apply {
                "name" to "Test"
                "count" to 42
                "active" to true
            }.build()

            assertThat(model)
                .containsEntry("name", "Test")
                .containsEntry("count", 42)
                .containsEntry("active", true)
        }
    }

    @Nested
    inner class HtmxFragmentTest {
        @Test
        fun `creates fragment with template only`() {
            val fragment = HtmxFragment("templates/list", null, emptyMap())

            assertThat(fragment.template).isEqualTo("templates/list")
            assertThat(fragment.fragmentName).isNull()
            assertThat(fragment.isOob).isFalse()
        }

        @Test
        fun `creates fragment with template and fragment name`() {
            val fragment = HtmxFragment("templates/list", "rows", mapOf("items" to listOf(1, 2, 3)))

            assertThat(fragment.template).isEqualTo("templates/list")
            assertThat(fragment.fragmentName).isEqualTo("rows")
            assertThat(fragment.model).containsEntry("items", listOf(1, 2, 3))
        }

        @Test
        fun `creates OOB fragment`() {
            val fragment = HtmxFragment("components/toast", "success", emptyMap(), isOob = true)

            assertThat(fragment.isOob).isTrue()
        }
    }

    @Nested
    inner class HtmxResponseBuilderTest {
        @Test
        fun `non-HTMX request with redirect handler returns redirect`() {
            val request = createNonHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows")
                onNonHtmx { redirect("/templates") }
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.SEE_OTHER)
            assertThat(response.headers().getFirst("Location")).isEqualTo("/templates")
        }

        @Test
        fun `HTMX request with single fragment returns OK`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list", "rows") {
                    "items" to listOf("a", "b")
                }
            }.build()

            assertThat(response.statusCode()).isEqualTo(HttpStatus.OK)
        }

        @Test
        fun `trigger header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("itemCreated")
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger")).isEqualTo("itemCreated")
        }

        @Test
        fun `trigger with detail is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("itemCreated", """{"id": 123}""")
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger"))
                .isEqualTo("""{"itemCreated": {"id": 123}}""")
        }

        @Test
        fun `pushUrl header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                pushUrl("/templates?page=2")
            }.build()

            assertThat(response.headers().getFirst("HX-Push-Url")).isEqualTo("/templates?page=2")
        }

        @Test
        fun `replaceUrl header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                replaceUrl("/templates")
            }.build()

            assertThat(response.headers().getFirst("HX-Replace-Url")).isEqualTo("/templates")
        }

        @Test
        fun `reswap header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                reswap(HxSwap.NONE)
            }.build()

            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("none")
        }

        @Test
        fun `retarget header is set correctly`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                retarget("#other-element")
            }.build()

            assertThat(response.headers().getFirst("HX-Retarget")).isEqualTo("#other-element")
        }

        @Test
        fun `multiple headers can be set`() {
            val request = createHtmxRequest()

            val response = HtmxResponseBuilder(request).apply {
                fragment("templates/list")
                trigger("saved")
                pushUrl("/new-url")
                reswap(HxSwap.OUTER_HTML)
            }.build()

            assertThat(response.headers().getFirst("HX-Trigger")).isEqualTo("saved")
            assertThat(response.headers().getFirst("HX-Push-Url")).isEqualTo("/new-url")
            assertThat(response.headers().getFirst("HX-Reswap")).isEqualTo("outerHTML")
        }
    }

    @Nested
    inner class HxSwapTest {
        @Test
        fun `all swap modes have correct values`() {
            assertThat(HxSwap.INNER_HTML.value).isEqualTo("innerHTML")
            assertThat(HxSwap.OUTER_HTML.value).isEqualTo("outerHTML")
            assertThat(HxSwap.BEFORE_BEGIN.value).isEqualTo("beforebegin")
            assertThat(HxSwap.AFTER_BEGIN.value).isEqualTo("afterbegin")
            assertThat(HxSwap.BEFORE_END.value).isEqualTo("beforeend")
            assertThat(HxSwap.AFTER_END.value).isEqualTo("afterend")
            assertThat(HxSwap.DELETE.value).isEqualTo("delete")
            assertThat(HxSwap.NONE.value).isEqualTo("none")
        }
    }

    @Nested
    inner class RedirectTest {
        @Test
        fun `redirect creates 303 See Other response`() {
            val response = redirect("/templates")

            assertThat(response.statusCode()).isEqualTo(HttpStatus.SEE_OTHER)
            assertThat(response.headers().getFirst("Location")).isEqualTo("/templates")
        }
    }

    private fun createHtmxRequest(): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        mockRequest.addHeader("HX-Request", "true")
        return ServerRequest.create(mockRequest, emptyList())
    }

    private fun createNonHtmxRequest(): ServerRequest {
        val mockRequest = MockHttpServletRequest()
        return ServerRequest.create(mockRequest, emptyList())
    }
}
