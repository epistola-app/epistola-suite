// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.error.KnownProblemSlugs
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.lang.reflect.Modifier

/**
 * The REST API answers only with the problem types the contract registers (`x-problem-types`, which
 * the server stubs generate as [KnownProblemSlugs]): "anything else is the generic type of its
 * status". A type added to [ApiProblemTypes], or a mapping pointed at one, fails here until the
 * contract registers it.
 */
class ProblemTypeRegistryTest {

    /** Every slug the contract dependency registers, read from the generated constants. */
    private val registered: Set<String> = KnownProblemSlugs::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

    @Test
    fun `the contract's registry is read`() {
        assertThat(registered).contains("not-found", "conflict", "validation-error", "bad-request")
    }

    @Test
    fun `every API problem type is registered by the contract`() {
        assertThat(ApiProblemTypes.all.map { it.slug } - registered - PENDING_CONTRACT)
            .`as`("problem types the contract does not register; use the generic type of their status")
            .isEmpty()
    }

    @Test
    fun `every mapped exception answers with an API problem type`() {
        assertThat(ApiExceptionMappings.registeredProblemTypes() - ApiProblemTypes.all.toSet()).isEmpty()
    }

    @Test
    fun `a validation failure of any code is validation-error on the API`() {
        val request = MockHttpServletRequest("POST", "/api/tenants/acme")
        val slugs = ValidationCode.entries.map { ValidationException(code = it, field = "f", message = "m").toValidationProblemDetail(request).type.toString() }.toSet()

        assertThat(slugs).containsExactly("$PROBLEM_TYPE_BASE_URL/validation-error")
    }

    @Test
    fun `the UI's finer types are not the API's`() {
        assertThat(UiProblemTypes.all.map { it.slug }.toSet() intersect ApiProblemTypes.all.map { it.slug }.toSet()).isEmpty()
    }

    @Test
    fun `the pending types are not registered yet`() {
        assertThat(PENDING_CONTRACT intersect registered)
            .`as`("registered by the contract now: drop them from PENDING_CONTRACT")
            .isEmpty()
    }

    companion object {
        /**
         * The generic types of the statuses contract 2.0.0-alpha.6 registers none for, which the
         * contract's `fix/error-responses` registers. Drop this set when the contract dependency
         * includes them; `the pending types are not registered yet` fails as soon as it does.
         */
        val PENDING_CONTRACT = setOf("method-not-allowed", "not-acceptable", "content-too-large", "unsupported-media-type", "internal-error")
    }
}
