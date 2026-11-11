// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.queries.GetTenantLogExcerpt
import app.epistola.suite.mediator.query
import app.epistola.suite.support.requests.ServerLogExcerpt
import app.epistola.suite.support.requests.SupportRequestLogSource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The suite's [SupportRequestLogSource]: the tenant's application log lines of the submitter's recent
 * requests, by trace id (`GetTenantLogExcerpt`), as recorded. Each line keeps its message as written in
 * the code beside it, so every reader can be given the excerpt its own privacy allows
 * ([ServerLogExcerpt.shaped]): pseudonymising here, once, would decide it for every support connection.
 */
@Component
class ApplicationLogSupportRequestSource(
    private val objectMapper: ObjectMapper,
) : SupportRequestLogSource {
    override fun excerpt(tenantKey: TenantKey, traceIds: List<String>): String? {
        val ids = traceIds.filter { TRACE_ID.matches(it) }.distinct().take(GetTenantLogExcerpt.MAX_TRACES)
        if (ids.isEmpty()) return null
        val excerpt = GetTenantLogExcerpt(tenantKey, ids).query()
        if (excerpt.lines.isEmpty()) return null
        val lines = objectMapper.createArrayNode()
        excerpt.lines.forEach { line ->
            lines.addObject().apply {
                put("time", line.occurredAt.toInstant().toString())
                put("level", line.level)
                put("logger", line.logger)
                put("message", line.message)
                line.messagePattern?.let { put(ServerLogExcerpt.PATTERN, it) }
                line.exception?.let { put("exception", it) }
                put("traceId", line.traceId)
            }
        }
        if (excerpt.truncated) {
            lines.addObject().put("level", "INFO").put("message", MORE_LEFT_OUT).put(ServerLogExcerpt.PATTERN, MORE_LEFT_OUT)
        }
        return objectMapper.writeValueAsString(lines)
    }

    companion object {
        private val TRACE_ID = Regex("[0-9a-f]{32}")
        private const val MORE_LEFT_OUT = "… more lines were left out"
    }
}
