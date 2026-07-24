// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import java.util.UUID

/**
 * Parses a string to UUID, returning null if parsing fails.
 */
fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: Exception) {
    null
}
