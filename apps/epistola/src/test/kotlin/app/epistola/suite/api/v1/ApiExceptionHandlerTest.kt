package app.epistola.suite.api.v1

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.ObjectError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.validation.FieldError as SpringFieldError

class ApiExceptionHandlerTest {

    @Test
    fun `method argument validation includes field and global errors`() {
        val target = ValidatedBody(name = null)
        val bindingResult = BeanPropertyBindingResult(target, "validatedBody")
        bindingResult.addError(SpringFieldError("validatedBody", "name", null, false, null, null, "Name is required"))
        bindingResult.addError(ObjectError("validatedBody", "Body-level rule failed"))
        val ex = MethodArgumentNotValidException(methodParameter(), bindingResult)

        val response = ApiExceptionHandler().handleMethodArgumentNotValidException(
            ex,
            MockHttpServletRequest("POST", "/api/test"),
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = response.body!!
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        @Suppress("UNCHECKED_CAST")
        val errors = body["errors"] as List<app.epistola.api.model.FieldError>
        assertThat(errors).extracting<String> { it.field }.containsExactly("name", "validatedBody")
        assertThat(errors).extracting<String> { it.message }.containsExactly("Name is required", "Body-level rule failed")
        assertThat(errors).extracting<Any?> { it.rejectedValue }.containsExactly(null, null)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun controllerMethod(body: ValidatedBody) = Unit

    private fun methodParameter(): MethodParameter {
        val method = javaClass.getDeclaredMethod("controllerMethod", ValidatedBody::class.java)
        return MethodParameter(method, 0)
    }

    private data class ValidatedBody(val name: String?)
}
