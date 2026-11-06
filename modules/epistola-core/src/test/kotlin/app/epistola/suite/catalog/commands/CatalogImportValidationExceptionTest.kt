// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.migration.CatalogMigrationCodes
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
    fun `a 1x dev label is told as an unreleased export, with what to do`() {
        val finding = CatalogImportValidationFinding.ofMigration(
            CatalogMigrationCodes.RELEASE_VERSION_INVALID,
            "catalog.json.release.version",
            "release version '1.2.0-dev' must be MAJOR.MINOR.PATCH from catalog v8; a pre-release or build part is not stripped",
        )

        assertThat(finding.severity).isEqualTo(ValidationSeverity.ERROR)
        assertThat(finding.code).isEqualTo(CatalogMigrationCodes.RELEASE_VERSION_INVALID)
        assertThat(finding.message)
            .contains("labelled '1.2.0-dev', which is not a release")
            .contains("Release the catalog in the installation it came from and export it again")
            .contains("set release.version in the archive's catalog.json")
            .endsWith("(such as 1.2.1)")
    }

    @Test
    fun `the release version advice holds without a version to quote`() {
        val finding = CatalogImportValidationFinding.ofMigration(
            CatalogMigrationCodes.RELEASE_VERSION_INVALID,
            "catalog.json.release.version",
            "release version is not valid",
        )

        assertThat(finding.message).contains("labelled a release version").doesNotContain("such as")
    }

    @Test
    fun `other migration findings keep the contract's message`() {
        val finding = CatalogImportValidationFinding.ofMigration("SOME_CODE", "catalog.json", "as the contract says")

        assertThat(finding).isEqualTo(CatalogImportValidationFinding("SOME_CODE", ValidationSeverity.ERROR, "catalog.json", "as the contract says"))
    }

    @Test
    fun `rejects construction without findings as a programmer error`() {
        assertThatThrownBy {
            CatalogImportValidationException(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("CatalogImportValidationException requires at least one finding")
    }
}
