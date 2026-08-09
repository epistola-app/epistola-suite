// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs

import app.epistola.suite.security.SecurityContext
import ch.qos.logback.classic.spi.ILoggingEvent
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer

/** Adds Epistola request context to Spring Boot's structured console output. */
class EpistolaStructuredLoggingJsonCustomizer : StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {
    override fun customize(members: JsonWriter.Members<ILoggingEvent>) {
        members.addMapEntries(
            JsonWriter.Extractor<ILoggingEvent, Map<String, String>> {
                resolveTenantId()?.let { mapOf(TENANT_ID_FIELD to it) } ?: emptyMap()
            },
        )
    }

    /**
     * Read on the logging thread, where the request/background principal's ScopedValue is bound.
     * Structured-logging customization must never make an otherwise safe log call fail.
     */
    private fun resolveTenantId(): String? = runCatching {
        SecurityContext.currentOrNull()?.currentTenantId?.value
    }.getOrNull()

    companion object {
        const val TENANT_ID_FIELD = "tenant_id"
    }
}
