// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

/**
 * Represents a single validation error from JSON Schema validation.
 */
data class ValidationError(
    val message: String,
    val path: String,
)
