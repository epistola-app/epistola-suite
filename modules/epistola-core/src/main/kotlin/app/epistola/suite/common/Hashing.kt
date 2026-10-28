// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common

import java.security.MessageDigest

/**
 * Lowercase hex SHA-256 of [bytes]: the one digest that pins stored content, a font face's or an
 * image's bytes as much as a catalog resource's canonical form. A font face uploaded again under the
 * same `(slug, weight, italic)` with other bytes therefore changes its family fingerprint, and a
 * published render that pinned the old one fails loudly.
 */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
