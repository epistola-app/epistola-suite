// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A support request's server log, shaped for whoever reads it. The request keeps the lines as
 * recorded ([SupportRequestLogSource]): each line's message with its values and, beside it, its
 * `pattern`, the message as written in the code. A reader gets them through [shaped]: a support
 * connection with its own telemetry setting (ADR 0053 §1), the installation's own pages with the
 * configured default, so one connection's setting never decides what another receives.
 */
object ServerLogExcerpt {
    const val OWN_PACKAGE = "app.epistola."
    const val LIBRARY_MESSAGE_WITHHELD = "(library message withheld)"
    const val MESSAGE_NOT_KEPT = "(message not kept)"

    /** Field of a stored line holding the message as written in the code; never sent or shown. */
    const val PATTERN = "pattern"

    /**
     * [stored] for a reader that is [pseudonymised] or not, as a JSON array of lines, oldest first.
     * Pseudonymised (ADR 0052 §2, the same rules as telemetry): the suite's own message as written in
     * the code, without its values; a library's not at all, as it may have built the values into it
     * itself; an exception's types and frames, without its messages. A line kept without its pattern
     * loses its message rather than giving its values. Text that is not a JSON array is withheld whole
     * when pseudonymised, as nothing in it can be told apart.
     */
    fun shaped(stored: String, pseudonymised: Boolean, objectMapper: ObjectMapper): String {
        val lines = runCatching { objectMapper.readTree(stored) }.getOrNull()
        if (lines == null || !lines.isArray) return if (pseudonymised) "[]" else stored
        val out = objectMapper.createArrayNode()
        lines.forEach { line ->
            if (line !is ObjectNode) return@forEach
            val copy = line.deepCopy()
            val pattern = copy.remove(PATTERN)?.takeIf { it.isString }?.stringValue()
            if (pseudonymised) {
                val logger = copy.get("logger")?.takeIf { it.isString }?.stringValue()
                copy.put(
                    "message",
                    when {
                        logger != null && !logger.startsWith(OWN_PACKAGE) -> LIBRARY_MESSAGE_WITHHELD
                        pattern != null -> pattern
                        else -> MESSAGE_NOT_KEPT
                    },
                )
                copy.get("exception")?.takeIf { it.isString }?.let { copy.put("exception", withoutMessages(it.stringValue())) }
            }
            out.add(copy)
        }
        return objectMapper.writeValueAsString(out)
    }

    /**
     * A rendered stack trace with only its exception types and frames: "Type: message" and
     * "Caused by: Type: message" lines lose the message, frames stay.
     */
    fun withoutMessages(trace: String): String = trace.lineSequence().joinToString("\n") { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("at ") || trimmed.startsWith("...") || trimmed.isEmpty() -> line

            else -> {
                val prefix = line.takeWhile { it.isWhitespace() } + listOf("Caused by: ", "Suppressed: ").firstOrNull { trimmed.startsWith(it) }.orEmpty()
                val type = trimmed.removePrefix("Caused by: ").removePrefix("Suppressed: ").substringBefore(':').substringBefore(' ')
                prefix + type
            }
        }
    }
}
