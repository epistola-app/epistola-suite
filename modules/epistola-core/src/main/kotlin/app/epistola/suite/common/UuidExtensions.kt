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
