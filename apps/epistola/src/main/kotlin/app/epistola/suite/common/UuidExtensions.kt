package app.epistola.suite.common

import org.springframework.web.servlet.function.ServerRequest
import java.util.UUID

/**
 * Gets a path variable as UUID, returning null if the variable doesn't exist or is invalid.
 */
fun ServerRequest.pathUuid(name: String): UUID? = pathVariable(name).toUuidOrNull()

/**
 * Gets a path variable as UUID, throwing IllegalArgumentException if invalid.
 *
 * @throws IllegalArgumentException if the path variable is not a valid UUID
 */
fun ServerRequest.requirePathUuid(name: String): UUID = pathUuid(name) ?: throw IllegalArgumentException("Invalid UUID for path variable: $name")

/**
 * Parses a string to UUID, returning null if parsing fails.
 */
private fun String.toUuidOrNull(): UUID? = try {
    UUID.fromString(this)
} catch (_: Exception) {
    null
}
