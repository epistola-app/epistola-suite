// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.migration.CatalogMigrationCodes
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
) {
    companion object {
        private val QUOTED_VERSION = Regex("'([^']*)'")

        /**
         * A finding of reading or migrating an archive, as an import reports it. A release number
         * wire v8 refuses is told as what it nearly always is, an Epistola 1.x export of a catalog
         * with unreleased changes (`0.0.0-dev`, `1.2.0-dev`), with what to do about it: the
         * contract's own message says only what the number must be.
         */
        fun ofMigration(code: String, path: String, message: String): CatalogImportValidationFinding = CatalogImportValidationFinding(
            code,
            ValidationSeverity.ERROR,
            path,
            if (code == CatalogMigrationCodes.RELEASE_VERSION_INVALID) releaseVersionAdvice(message) else message,
        )

        private fun releaseVersionAdvice(contractMessage: String): String {
            val version = QUOTED_VERSION.find(contractMessage)?.groupValues?.get(1)
            val named = version?.let { "'$it'" } ?: "a release version"
            val example = version?.let(::nextPatch)?.let { " (such as $it)" }.orEmpty()
            return "the archive is labelled $named, which is not a release: Epistola 1.x labels an export " +
                "of a catalog that was never released, or has changes since its last release, with '-dev'. " +
                "A release version is MAJOR.MINOR.PATCH. Release the catalog in the installation it came " +
                "from and export it again, or set release.version in the archive's catalog.json to a " +
                "version no other release of this catalog has$example"
        }

        /** The patch after the number a label carries (`1.2.0-dev` → `1.2.1`): above the release it was exported after. */
        private fun nextPatch(version: String): String? {
            val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(version)?.destructured ?: return null
            return "$major.$minor.${patch.toBigInteger() + java.math.BigInteger.ONE}"
        }
    }
}

private fun List<CatalogImportValidationFinding>.sortedDeterministically(): List<CatalogImportValidationFinding> = sortedWith(
    compareBy({ it.path }, { it.code }, { it.severity }, { it.message }),
)
