package app.epistola.suite.mediator

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.TenantScoped
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
) {

    private val logger = LoggerFactory.getLogger(EventLogSubscriber::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun persist(event: CommandCompleted<*>) {
        try {
            val command = event.command
            val eventType = command::class.simpleName ?: "Unknown"
            val tenantId = (command as? TenantScoped)?.tenantId?.value
            val entityId = extractEntityId(command)
            val payload = objectMapper.writeValueAsString(command)

            jdbi.withHandle<Unit, Exception> { handle ->
                handle.createUpdate(
                    """
                    INSERT INTO event_log (event_type, tenant_id, entity_id, payload, occurred_at)
                    VALUES (:eventType, :tenantId, :entityId, :payload::jsonb, :occurredAt)
                    """,
                ).apply {
                    bind("eventType", eventType)
                    bind("tenantId", tenantId)
                    bind("entityId", entityId)
                    bind("payload", payload)
                    bind("occurredAt", event.occurredAt)
                }.execute()
            }

            logger.debug("Event logged: {} (tenant={})", eventType, tenantId)
        } catch (e: Exception) {
            // Failure to log should not affect the command
            logger.error("Failed to persist event log: {}", e.message, e)
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
