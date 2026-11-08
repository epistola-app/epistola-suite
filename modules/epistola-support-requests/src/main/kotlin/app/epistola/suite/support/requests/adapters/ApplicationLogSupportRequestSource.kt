// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.queries.GetTenantLogExcerpt
import app.epistola.suite.logs.queries.TenantLogLine
import app.epistola.suite.mediator.query
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.requests.SupportRequestLogSource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The suite's [SupportRequestLogSource]: the tenant's application log lines of the submitter's recent
 * requests, by trace id (`GetTenantLogExcerpt`). Pseudonymised like telemetry when the installation
 * pseudonymises telemetry (ADR 0052 §2): a line's message as written in the code, without its values,
 * and an exception's types and frames, without its messages.
 */
@Component
class ApplicationLogSupportRequestSource(
    private val privacy: SupportPrivacy,
    private val objectMapper: ObjectMapper,
) : SupportRequestLogSource {
    override fun excerpt(tenantKey: TenantKey, traceIds: List<String>): String? {
        val ids = traceIds.filter { TRACE_ID.matches(it) }.distinct().take(GetTenantLogExcerpt.MAX_TRACES)
        if (ids.isEmpty()) return null
        val excerpt = GetTenantLogExcerpt(tenantKey, ids).query()
        if (excerpt.lines.isEmpty()) return null
        val pseudonymised = privacy.current().pseudonymised
        val lines = objectMapper.createArrayNode()
        excerpt.lines.forEach { line ->
            lines.addObject().apply {
                put("time", line.occurredAt.toInstant().toString())
                put("level", line.level)
                put("logger", line.logger)
                put("message", if (pseudonymised) line.pattern() else line.message)
                line.exception?.let { put("exception", if (pseudonymised) withoutMessages(it) else it) }
                put("traceId", line.traceId)
            }
        }
        if (excerpt.truncated) lines.addObject().put("level", "INFO").put("message", "… more lines were left out")
        return objectMapper.writeValueAsString(lines)
    }

    /** The pattern; a line stored before patterns were kept has none, so nothing of its text goes. */
    private fun TenantLogLine.pattern(): String = messagePattern ?: "(message not kept)"

    companion object {
        private val TRACE_ID = Regex("[0-9a-f]{32}")

        /**
         * A rendered stack trace with only its exception types and frames: "Type: message" and
         * "Caused by: Type: message" lines lose the message, frames stay.
         */
        internal fun withoutMessages(trace: String): String = trace.lineSequence().joinToString("\n") { line ->
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
}
