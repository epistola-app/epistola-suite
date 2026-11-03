// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.error.KnownValidationCodes
import app.epistola.suite.validation.ValidationCode
import jakarta.validation.ConstraintViolation

/**
 * The `code` of a `validation-error`'s entries: why a field failed, from the contract's
 * `x-validation-codes` ([KnownValidationCodes]). A code is optional, so an entry goes out without
 * one rather than with a code the contract does not register.
 */
object ApiValidationCodes {
    private val registered: Set<String> = KnownValidationCodes.ALL.toSet()

    /**
     * The registered code of a [ValidationCode]: its wire name in kebab-case, when the contract
     * registers it (the template validator's codes, and the domain rules a caller can act on).
     */
    fun of(code: ValidationCode): String? = code.wire.lowercase().replace('_', '-').takeIf { it in registered }

    /** The constraint code of a Bean Validation [violation]: the JSON Schema keyword it stands for. */
    fun of(violation: ConstraintViolation<*>): String? = ofConstraint(
        violation.constraintDescriptor.annotation.annotationClass.simpleName,
        violation.invalidValue,
        violation.constraintDescriptor.attributes,
    )

    /**
     * The constraint code of the Bean Validation constraint [annotation] that [value] broke. The
     * generated interfaces validate with the constraints the contract's schema keywords become:
     * `@NotNull` for `required`, `@Size` for `minLength`/`maxLength` and `minItems`/`maxItems`,
     * `@Pattern`, `@Min`/`@Max` (and the decimal ones), `@Email` for `format`.
     */
    fun ofConstraint(annotation: String?, value: Any?, attributes: Map<String, Any?> = emptyMap()): String? = when (annotation) {
        "NotNull" -> KnownValidationCodes.REQUIRED

        "NotBlank", "NotEmpty" -> when {
            value == null -> KnownValidationCodes.REQUIRED
            sizeOf(value) != null && value !is CharSequence -> KnownValidationCodes.MIN_ITEMS
            else -> KnownValidationCodes.MIN_LENGTH
        }

        "Size" -> sizeCode(value, attributes)

        "Pattern" -> KnownValidationCodes.PATTERN

        "Min", "DecimalMin", "Positive", "PositiveOrZero" -> KnownValidationCodes.MINIMUM

        "Max", "DecimalMax", "Negative", "NegativeOrZero" -> KnownValidationCodes.MAXIMUM

        "Email" -> KnownValidationCodes.FORMAT

        else -> null
    }

    /** `@Size` is both bounds: the one [value] broke, for text or for a list. */
    private fun sizeCode(value: Any?, attributes: Map<String, Any?>): String? {
        val size = sizeOf(value) ?: return null
        val tooLong = (attributes["max"] as? Int)?.let { size > it } == true
        return when {
            value is CharSequence -> if (tooLong) KnownValidationCodes.MAX_LENGTH else KnownValidationCodes.MIN_LENGTH
            else -> if (tooLong) KnownValidationCodes.MAX_ITEMS else KnownValidationCodes.MIN_ITEMS
        }
    }

    private fun sizeOf(value: Any?): Int? = when (value) {
        is CharSequence -> value.length
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        is Array<*> -> value.size
        else -> null
    }
}
