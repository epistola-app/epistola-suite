// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.suite.validation.ValidationException

/**
 * Runs [block], naming a refused field as the request names it: the domain's [domainField] is the
 * contract's [requestField] (a variant's `title` and an attribute's or code list's `displayName` are
 * `name` in the contract), so a 400 points at a member the caller sent.
 */
internal inline fun <T> requestField(domainField: String, requestField: String, block: () -> T): T = try {
    block()
} catch (e: ValidationException) {
    if (e.field != domainField) throw e
    throw ValidationException(requestField, e.message, e.code)
}
