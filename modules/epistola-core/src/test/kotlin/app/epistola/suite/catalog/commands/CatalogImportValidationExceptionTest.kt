// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.validation.ValidationSeverity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogImportValidationExceptionTest {
    @Test
    fun `retains findings in deterministic path and code order`() {
        val exception = CatalogImportValidationException(
            listOf(
                CatalogImportValidationFinding("Z_CODE", ValidationSeverity.WARNING, "resources/z.json", "last"),
                CatalogImportValidationFinding("B_CODE", ValidationSeverity.ERROR, "catalog.json", "second"),
                CatalogImportValidationFinding("A_CODE", ValidationSeverity.ERROR, "catalog.json", "first"),
            ),
        )

        assertThat(exception.findings.map { it.code })
            .containsExactly("A_CODE", "B_CODE", "Z_CODE")
        assertThat(exception.findings.map { it.severity })
            .containsExactly(ValidationSeverity.ERROR, ValidationSeverity.ERROR, ValidationSeverity.WARNING)
        assertThat(exception.message)
            .isEqualTo(
                "Catalog validation failed: " +
                    "A_CODE at catalog.json: first; " +
                    "B_CODE at catalog.json: second; " +
                    "Z_CODE at resources/z.json: last",
            )
    }

    @Test
    fun `rejects construction without findings as a programmer error`() {
        assertThatThrownBy {
            CatalogImportValidationException(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("CatalogImportValidationException requires at least one finding")
    }
}
