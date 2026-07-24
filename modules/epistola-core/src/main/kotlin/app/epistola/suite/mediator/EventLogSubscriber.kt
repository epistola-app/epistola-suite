// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.NotEventLogged
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.UUIDv7
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.ObjectMapper

/**
 * Subscribes to all CommandCompleted events and persists them to the event_log table.
 *
 * This provides:
 * - Append-only audit trail of all commands
 * - Tenant-based event queries for compliance/debugging
 * - Foundation for future event replay/recovery
 * - Metrics and observability
 *
 * Runs AFTER_COMMIT to ensure only successful commands are logged.
 * Failures are logged but don't affect the command.
 */
@Component
class EventLogSubscriber(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {

    private val logger = LoggerFactory.getLogger(EventLogSubscriber::class.java)
    private val persistFailures = Counter.builder("epistola.eventlog.persist.failures")
        .description("Event log persistence failures (audit trail gaps)")
        .register(meterRegistry)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun persist(event: CommandCompleted<*>) {
        // Commands opted out of the event stream (high-volume, low-signal work tracked in its
        // own system of record — e.g. the document-generation path, recorded in generation_results).
        if (event.command is NotEventLogged) return

        val sample = Timer.start()
        var outcome = "success"
        try {
            val command = event.command
            val eventType = command::class.simpleName ?: "Unknown"
            val tenantId = (command as? TenantScoped)?.tenantId?.value
            val entityId = extractEntityId(command)
            val payload = objectMapper.writeValueAsString(command)

            jdbi.withHandle<Unit, Exception> { handle ->
                handle.createUpdate(
                    """
                    INSERT INTO event_log (id, event_type, tenant_key, entity_id, payload, occurred_at)
                    VALUES (:id, :eventType, :tenantId, :entityId, :payload::jsonb, :occurredAt)
                    """,
                ).apply {
                    bind("id", UUIDv7.generate())
                    bind("eventType", eventType)
                    bind("tenantId", tenantId)
                    bind("entityId", entityId)
                    bind("payload", payload)
                    bind("occurredAt", event.occurredAt)
                }.execute()
            }

            logger.debug("Event logged: {} (tenant={})", eventType, tenantId)
        } catch (e: Exception) {
            outcome = "failure"
            persistFailures.increment()
            // Failure to log should not affect the command
            logger.error("Failed to persist event log: {}", e.message, e)
        } finally {
            sample.stop(
                Timer.builder("epistola.eventlog.persist.duration")
                    .description("Event log persistence latency")
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }

    private fun extractEntityId(command: Command<*>): String? {
        // Extract the primary entity ID from the command
        // EntityIdentifiable takes precedence: it's the primary entity being operated on
        return when {
            command is EntityIdentifiable -> command.entityId
            else -> null
        }
    }
}
