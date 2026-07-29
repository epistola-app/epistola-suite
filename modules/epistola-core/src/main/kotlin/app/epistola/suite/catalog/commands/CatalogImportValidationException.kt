// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.validation.ValidationSeverity

/**
 * Rejects a catalog import whose archive or portable catalog content is invalid.
 *
 * The portable contract returns findings rather than throwing for ordinary
 * invalid input. Suite converts those findings at its command boundary while
 * retaining their stable codes and paths for callers that need structured
 * diagnostics. Extending [IllegalArgumentException] preserves the existing
 * bad-request presentation for API and UI consumers.
 */
class CatalogImportValidationException(
    findings: List<CatalogImportValidationFinding>,
) : IllegalArgumentException(message(findings.sortedDeterministically())) {
    val findings: List<CatalogImportValidationFinding> = findings.sortedDeterministically()

    init {
        require(this.findings.isNotEmpty()) {
            "CatalogImportValidationException requires at least one finding"
        }
    }

    companion object {
        private fun message(findings: List<CatalogImportValidationFinding>): String {
            require(findings.isNotEmpty()) {
                "CatalogImportValidationException requires at least one finding"
            }
            return findings.joinToString(
                prefix = "Catalog validation failed: ",
                separator = "; ",
            ) { "${it.code} at ${it.path}: ${it.message}" }
        }
    }
}

data class CatalogImportValidationFinding(
    val code: String,
    val severity: ValidationSeverity,
    val path: String,
    val message: String,
)

private fun List<CatalogImportValidationFinding>.sortedDeterministically(): List<CatalogImportValidationFinding> = sortedWith(
    compareBy({ it.path }, { it.code }, { it.severity }, { it.message }),
)
