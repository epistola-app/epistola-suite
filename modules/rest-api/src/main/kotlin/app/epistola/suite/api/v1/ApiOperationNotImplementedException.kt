// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

class ApiOperationNotImplementedException(
    val operation: String,
) : RuntimeException("API operation '$operation' is not implemented")
