// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.validation

/**
 * One JSON Schema violation, with the structure [ValidationError] flattens away.
 *
 * @property keyword The schema keyword that failed (`required`, `type`, `enum`, …)
 * @property pointer JSON Pointer (RFC 6901) to the data location the keyword was evaluated against.
 *   For `required` that is the *parent* object; [property] names the absent key.
 * @property property The property the keyword is about, when it names one (`required`, `additionalProperties`)
 * @property message Human-readable message from the validator
 * @property conditional True when the keyword was evaluated inside `anyOf`/`oneOf`/`not`/`if`/`then`/`else`,
 *   so it only applies to one branch and does not on its own mean the data needs that change
 */
data class SchemaViolation(
    val keyword: String,
    val pointer: String,
    val property: String?,
    val message: String,
    val conditional: Boolean,
)
