// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.support

import app.epistola.suite.validation.ValidationException
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Structured tool arguments arrive as JSON strings, the convention `preview_document` set for
 * its `data` argument: a string parameter keeps the tool's input schema flat, and the domain
 * types (template documents, theme styles) are validated by the same Jackson binding and
 * commands the REST API uses rather than by a second, generated schema.
 *
 * A malformed argument is reported against its parameter name, so the assistant can see which
 * argument to fix.
 */
internal fun <T : Any> ObjectMapper.parseArgument(field: String, json: String, type: Class<T>): T = try {
    readValue(json, type) ?: throw ValidationException(field, "`$field` must not be JSON null")
} catch (e: JacksonException) {
    throw ValidationException(field, "`$field` is not a valid ${type.simpleName}: ${e.originalMessage}")
}

/** [parseArgument] for an optional argument: blank or absent means "not given". */
internal fun <T : Any> ObjectMapper.parseOptionalArgument(field: String, json: String?, type: Class<T>): T? = json?.takeIf { it.isNotBlank() }?.let { parseArgument(field, it, type) }

/** Binary content handed to a tool, with the media type a `data:` URL declared, if any. */
internal data class DecodedContent(val bytes: ByteArray, val declaredMediaType: String?)

/**
 * Decode base64 content. Accepts plain base64 (line breaks allowed) or a `data:<type>;base64,`
 * URL, which is what an assistant holding an extracted image most often has at hand.
 */
internal fun decodeBase64Argument(field: String, value: String): DecodedContent {
    val trimmed = value.trim()
    var declaredMediaType: String? = null
    var payload = trimmed
    if (trimmed.startsWith("data:")) {
        val comma = trimmed.indexOf(',')
        val header = if (comma > 0) trimmed.substring(5, comma) else ""
        if (comma < 0 || !header.endsWith(";base64")) {
            throw ValidationException(field, "`$field` is a data URL but not a base64 one")
        }
        declaredMediaType = header.removeSuffix(";base64").takeIf { it.isNotBlank() }
        payload = trimmed.substring(comma + 1)
    }
    val bytes = try {
        Base64.getMimeDecoder().decode(payload)
    } catch (e: IllegalArgumentException) {
        throw ValidationException(field, "`$field` is not valid base64: ${e.message}")
    }
    if (bytes.isEmpty()) throw ValidationException(field, "`$field` is empty")
    return DecodedContent(bytes, declaredMediaType)
}

/**
 * A write tool addressed something that does not exist. Read tools answer `null` for that; a
 * write has no result to be empty, so it fails with a message naming the address.
 */
internal fun notFound(kind: String, catalogId: String, key: String) = NoSuchElementException("$kind '$catalogId/$key' not found")
