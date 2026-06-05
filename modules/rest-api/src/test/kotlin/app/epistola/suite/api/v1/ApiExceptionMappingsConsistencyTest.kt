package app.epistola.suite.api.v1

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.ExceptionHandler

/**
 * Consistency guard: the [ApiExceptionMappings] registry and the
 * [ApiExceptionHandler.handleMappedApiException] annotation must stay in sync.
 *
 * Adding a new mapped exception in one place but forgetting the other produces
 * a runtime mismatch (Spring routes the exception to the catch-all handler or
 * fails to compile). These tests fail fast at build time instead.
 */
class ApiExceptionMappingsConsistencyTest {

    @Test
    fun `every exception registered in ApiExceptionMappings is listed in handleMappedApiException`() {
        val annotated = handlerAnnotatedClasses()
        val registered = ApiExceptionMappings.registeredClasses()

        val missingFromHandler = registered - annotated
        assertThat(missingFromHandler)
            .describedAs("Registered in ApiExceptionMappings but missing from @ExceptionHandler annotation")
            .isEmpty()
    }

    @Test
    fun `every exception listed in handleMappedApiException is registered in ApiExceptionMappings`() {
        val annotated = handlerAnnotatedClasses()
        val registered = ApiExceptionMappings.registeredClasses()

        val missingFromRegistry = annotated - registered
        assertThat(missingFromRegistry)
            .describedAs("Listed in @ExceptionHandler but missing from ApiExceptionMappings registry")
            .isEmpty()
    }

    private fun handlerAnnotatedClasses(): Set<Class<out Throwable>> = ApiExceptionHandler::class.java
        .getMethod("handleMappedApiException", Throwable::class.java, HttpServletRequest::class.java)
        .getAnnotation(ExceptionHandler::class.java)
        .value
        .map { it.java }
        .toSet()
}
