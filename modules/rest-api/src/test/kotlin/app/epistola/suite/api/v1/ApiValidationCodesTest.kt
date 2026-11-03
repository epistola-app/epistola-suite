// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.api.error.KnownValidationCodes
import app.epistola.suite.validation.ValidationCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiValidationCodesTest {

    @Test
    fun `a constraint becomes the schema keyword it stands for`() {
        assertThat(ApiValidationCodes.ofConstraint("NotNull", null)).isEqualTo("required")
        assertThat(ApiValidationCodes.ofConstraint("Pattern", "A B")).isEqualTo("pattern")
        assertThat(ApiValidationCodes.ofConstraint("Min", 0)).isEqualTo("minimum")
        assertThat(ApiValidationCodes.ofConstraint("DecimalMax", 9)).isEqualTo("maximum")
        assertThat(ApiValidationCodes.ofConstraint("Email", "x")).isEqualTo("format")
        assertThat(ApiValidationCodes.ofConstraint("SomethingElse", "x")).isNull()
    }

    @Test
    fun `size names the bound the value broke, for text and for lists`() {
        val bounds = mapOf("min" to 1, "max" to 3)
        assertThat(ApiValidationCodes.ofConstraint("Size", "", bounds)).isEqualTo("min-length")
        assertThat(ApiValidationCodes.ofConstraint("Size", "abcd", bounds)).isEqualTo("max-length")
        assertThat(ApiValidationCodes.ofConstraint("Size", emptyList<String>(), bounds)).isEqualTo("min-items")
        assertThat(ApiValidationCodes.ofConstraint("Size", listOf(1, 2, 3, 4), bounds)).isEqualTo("max-items")
        assertThat(ApiValidationCodes.ofConstraint("NotBlank", "")).isEqualTo("min-length")
        assertThat(ApiValidationCodes.ofConstraint("NotBlank", null)).isEqualTo("required")
    }

    @Test
    fun `a suite code goes out only when the contract registers it`() {
        assertThat(ApiValidationCodes.of(ValidationCode.CATALOG_RESOURCES_NOT_READY)).isEqualTo("catalog-resources-not-ready")
        assertThat(ApiValidationCodes.of(ValidationCode.STENCIL_RECURSION)).isEqualTo("stencil-recursion")
        assertThat(ApiValidationCodes.of(ValidationCode.GENERIC)).isNull()
        assertThat(ApiValidationCodes.of(ValidationCode.CLUSTER_NODE_NOT_FOUND)).isNull()
    }

    @Test
    fun `every code the API can send is one the contract registers`() {
        val sent = ValidationCode.entries.mapNotNull(ApiValidationCodes::of) +
            listOf("NotNull", "NotBlank", "Size", "Pattern", "Min", "Max", "Email").flatMap { constraint ->
                listOf("", "abcd", emptyList<String>(), null).mapNotNull { ApiValidationCodes.ofConstraint(constraint, it, mapOf("min" to 1, "max" to 3)) }
            }
        assertThat(KnownValidationCodes.ALL).containsAll(sent)
    }

    @Test
    fun `the suite can send every template and domain code the contract registers`() {
        // The registry's template codes come from the catalog's validator, which the suite runs;
        // a code the suite has no ValidationCode for could never reach a caller.
        val suite = ValidationCode.entries.mapNotNull(ApiValidationCodes::of).toSet()
        val constraintCodes = setOf("required", "type", "enum", "format", "pattern", "min-length", "max-length", "minimum", "maximum", "min-items", "max-items")
        assertThat(suite).containsExactlyInAnyOrderElementsOf(KnownValidationCodes.ALL - constraintCodes)
    }
}
