package app.epistola.suite.audit

import app.epistola.suite.common.AuditedRead
import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.NotAudited
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.EntityIdBase
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandListener
import app.epistola.suite.mediator.DispatchOutcome
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryListener
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/** Read vs write — the access dimension recorded on each `audit_log` row. */
private const val OPERATION_WRITE = "WRITE"
private const val OPERATION_READ = "READ"

/**
 * Writes PII-free entries to the `audit_log` table — the "who did what, when"
 * trail. Registered as both a [CommandListener] and a [QueryListener], so
 * [app.epistola.suite.mediator.SpringMediator] invokes it for every command and
 * query it dispatches, on both the success and failure paths.
 *
 * Polarity differs by side:
 * - **Commands** are audited (`operation = WRITE`) unless they are `SystemInternal`
 *   (background/system work) or marked [NotAudited] (high-frequency opt-out).
 * - **Queries** are audited (`operation = READ`) only when marked [AuditedRead]
 *   (deliberate, sensitive data-access reads), because most queries are
 *   high-volume internal reads.
 *
 * What is recorded: the actor (an opaque [app.epistola.suite.common.ids.UserKey]
 * surrogate), the tenant, the action (class name), the entity touched, the
 * operation, the outcome, and — on failure — a machine-readable, non-PII error
 * code. No payload, no free text, so the table never holds personal information.
 *
 * Durability: each entry is written in its **own** JDBI handle (a separate
 * connection, independent of any handler transaction) so a failing command's
 * rollback cannot erase its FAILURE entry. Recording is best-effort and must
 * never affect the dispatch — failures are swallowed, counted, and logged,
 * mirroring [app.epistola.suite.mediator.EventLogSubscriber].
 */
@Component
class AuditRecorder(
    private val jdbi: Jdbi,
    private val nodeIdentity: NodeIdentity,
    meterRegistry: MeterRegistry,
) : CommandListener,
    QueryListener {
    private val logger = LoggerFactory.getLogger(AuditRecorder::class.java)
    private val persistFailures = Counter.builder("epistola.auditlog.persist.failures")
        .description("Audit log persistence failures (audit trail gaps)")
        .register(meterRegistry)

    /** Per-message-class cache of the `EntityId`-typed properties, in constructor order. */
    private val entityIdProps = ConcurrentHashMap<KClass<*>, List<KProperty1<Any, *>>>()

    override fun onCommand(
        command: Command<*>,
        outcome: DispatchOutcome,
        error: Throwable?,
    ) {
        if (command is SystemInternal || command is NotAudited) return
        write(command, OPERATION_WRITE, outcome, error)
    }

    override fun onQuery(
        query: Query<*>,
        outcome: DispatchOutcome,
        error: Throwable?,
    ) {
        // Read auditing is opt-in: only deliberately marked, sensitive reads.
        if (query !is AuditedRead) return
        write(query, OPERATION_READ, outcome, error)
    }

    private fun write(
        message: Any,
        operation: String,
        outcome: DispatchOutcome,
        error: Throwable?,
    ) {
        try {
            val entity = entityRef(message)
            jdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    INSERT INTO audit_log
                        (id, occurred_at, tenant_key, actor_user_id, action, operation,
                         entity_type, entity_id, outcome, error_code, instance_id)
                    VALUES
                        (:id, :occurredAt, :tenantKey, :actorUserId, :action, :operation,
                         :entityType, :entityId, :outcome, :errorCode, :instanceId)
                    """,
                )
                    .bind("id", UUIDv7.generate())
                    .bind("occurredAt", EpistolaClock.instant())
                    .bind("tenantKey", tenantKeyOf(message))
                    .bind("actorUserId", currentUserIdOrNull()?.value)
                    .bind("action", message::class.simpleName ?: "Unknown")
                    .bind("operation", operation)
                    .bind("entityType", entity?.type)
                    .bind("entityId", entity?.id)
                    .bind("outcome", outcome.name)
                    .bind("errorCode", errorCode(error))
                    .bind("instanceId", nodeIdentity.nodeId)
                    .execute()
            }
        } catch (e: Exception) {
            persistFailures.increment()
            logger.error(
                "Failed to persist audit log entry for {}: {}",
                message::class.simpleName,
                e.message,
                e,
            )
        }
    }

    /**
     * The tenant a message is scoped to. Most commands/queries carry it via
     * [RequiresPermission.tenantKey] (the permission-gated path) rather than the
     * [TenantScoped] marker, so we read both. Root/cross-tenant messages
     * (`RequiresPlatformRole`, `RequiresAuthentication`) have no tenant → null.
     */
    private fun tenantKeyOf(message: Any): String? = when (message) {
        is TenantScoped -> message.tenantId.value
        is RequiresPermission -> message.tenantKey.value
        else -> null
    }

    private data class EntityRef(val type: String?, val id: String)

    /**
     * The entity a message acted on, derived generically so it works without each
     * command/query opting in. A typed [EntityIdBase] property (e.g. `id: ThemeId`,
     * `variantId: VariantId`) yields both the type and a non-PII reference via its
     * `path()` ("`<type>:<segments>`", e.g. `template:acme/default/invoice`). When
     * a message instead implements [EntityIdentifiable] explicitly, that id is used.
     */
    private fun entityRef(message: Any): EntityRef? {
        entityIdBaseOf(message)?.let { idBase ->
            val path = idBase.path()
            return EntityRef(type = path.substringBefore(':'), id = path.substringAfter(':'))
        }
        (message as? EntityIdentifiable)?.let { return EntityRef(type = null, id = it.entityId) }
        return null
    }

    /** First [EntityIdBase]-valued property of the message (constructor order), or null. */
    @Suppress("UNCHECKED_CAST")
    private fun entityIdBaseOf(message: Any): EntityIdBase? {
        val props = entityIdProps.computeIfAbsent(message::class) { kls ->
            val ctorOrder = kls.primaryConstructor?.parameters
                ?.mapIndexedNotNull { index, p -> p.name?.let { it to index } }
                ?.toMap()
                ?: emptyMap()
            kls.memberProperties
                .filter { (it.returnType.classifier as? KClass<*>)?.isSubclassOf(EntityIdBase::class) == true }
                .sortedBy { ctorOrder[it.name] ?: Int.MAX_VALUE }
                .onEach { it.isAccessible = true }
                .map { it as KProperty1<Any, *> }
        }
        return props.firstNotNullOfOrNull { it.get(message) as? EntityIdBase }
    }

    /**
     * A machine-readable, **PII-free** failure code. Never the exception message
     * (messages can echo user input). Prefers the first-class
     * [ValidationException.code]; otherwise the exception's class name.
     */
    private fun errorCode(error: Throwable?): String? = when (error) {
        null -> null
        is ValidationException -> error.code.name
        else -> error::class.simpleName
    }
}
