package app.epistola.suite.templates.validation

import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.AbstractThrowableAssert
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.ThrowingConsumer

/**
 * Asserts the thrown [ValidationException] carries [expected] as its
 * machine-readable [ValidationException.code]. Replaces the old
 * `hasMessageContaining("SOME_CODE")` assertions now that the code is a
 * first-class field rather than a message prefix. Returns the same throwable
 * assert so human-text `hasMessageContaining(...)` checks can still chain.
 */
fun AbstractThrowableAssert<*, *>.hasValidationCode(
    expected: ValidationCode,
): AbstractThrowableAssert<*, *> = satisfies(
    ThrowingConsumer<Throwable> { t ->
        assertThat((t as ValidationException).code).isEqualTo(expected)
    },
)
