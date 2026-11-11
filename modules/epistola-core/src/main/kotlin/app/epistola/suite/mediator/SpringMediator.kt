// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.security.Authorized
import app.epistola.suite.security.AuthorizedOperation
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.currentUser
import app.epistola.suite.security.requirePermission
import app.epistola.suite.security.requirePlatformRole
import app.epistola.suite.security.requireTenantAccess
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Spring-based implementation of the Mediator pattern with eventing.
 *
 * Automatically discovers CommandHandler and QueryHandler beans and routes
 * commands/queries to the appropriate handler.
 *
 * Every command dispatch runs inside ONE Spring-managed transaction (unless the
 * command opts out via [SelfManagedTransaction]) that spans:
 * 1. CommandHandler.handle()
 * 2. IMMEDIATE EventHandlers (same transaction — a throwing handler rolls the command back)
 * 3. CommandCompleted publication (so AFTER_COMMIT TransactionalEventListeners, incl.
 *    EventLogSubscriber's audit-trail write, bind to the command's transaction and fire
 *    only after it actually commits)
 *
 * JDBI joins this transaction via SpringConnectionFactory + SpringAwareTransactionHandler,
 * so nested `jdbi.inTransaction { }` in handlers participates instead of committing early.
 *
 * A message that names a catalog version ([CatalogReleaseScoped]) carries it to its handler, which
 * resolves it and binds it to its statements (ADR 0049); the mediator sets nothing for it, so nothing
 * is inherited by the messages a handler dispatches. It only refuses a command that names a release.
 *
 * Uses lazy handler discovery to support handlers that are initialized late
 * (e.g., handlers with Spring Batch job dependencies).
 */
@Component
class SpringMediator(
    private val applicationContext: ApplicationContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
    private val commandListeners: List<CommandListener>,
    private val queryListeners: List<QueryListener>,
    private val transactionManager: PlatformTransactionManager?,
) : Mediator {

    private val log = LoggerFactory.getLogger(SpringMediator::class.java)

    // Cache handlers but allow lazy discovery for late-initialized beans
    // Thread-safe: use ConcurrentHashMap to prevent data races under concurrent requests
    private val commandHandlersCache: MutableMap<KClass<*>, CommandHandler<*, *>> =
        ConcurrentHashMap()
    private val queryHandlersCache: MutableMap<KClass<*>, QueryHandler<*, *>> =
        ConcurrentHashMap()
    private val eventHandlersCache: MutableMap<KClass<*>, List<EventHandler<*>>> =
        ConcurrentHashMap()

    override fun <R> send(command: Command<R>): R = withExecutionContext {
        DispatchMdc.around(DispatchMdc.COMMAND, command::class.simpleName ?: "Unknown", command) { dispatch(command) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R> dispatch(command: Command<R>): R {
        enforceAuthorization(command)
        refuseWriteToRelease(command)

        val commandName = command::class.simpleName ?: "Unknown"
        log.debug("Dispatching command: {}", commandName)

        val handler = commandHandlersCache.computeIfAbsent(command::class) {
            findCommandHandler(command::class)
        } as? CommandHandler<Command<R>, R>
            ?: throw IllegalArgumentException("No handler found for command: $commandName").also {
                log.error("No handler found for command: {}", commandName)
            }

        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        return try {
            // One transaction around handler + IMMEDIATE events + event publication:
            // a throwing IMMEDIATE handler rolls the command back, and AFTER_COMMIT
            // listeners (EventLogSubscriber) bind to this transaction.
            val result = handlingIfAuthorized(command) {
                inCommandTransaction(command) {
                    val handled = handler.handle(command)
                    log.debug("Command {} completed successfully", commandName)

                    // Phase 1: Invoke IMMEDIATE event handlers (same transaction/call stack)
                    invokeEventHandlers(command, handled, EventPhase.IMMEDIATE)

                    // Phase 2: Publish Spring event for AFTER_COMMIT handlers and EventLogSubscriber
                    eventPublisher.publishEvent(CommandCompleted(command, handled))

                    handled
                }
            }

            // Notify cross-cutting listeners (audit, …) of the successful command.
            // After the transaction committed so a handler that rolls the command back
            // is reported as a failure (catch below), not a success.
            notifyCommandListeners(command, DispatchOutcome.SUCCESS, null)

            result
        } catch (e: Exception) {
            outcome = "failure"
            log.warn("Command {} failed: {}", commandName, e.message)
            notifyCommandListeners(command, DispatchOutcome.FAILURE, e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("epistola.mediator.command.duration")
                    .tag("command", commandName)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }

    override fun <R> query(query: Query<R>): R = withExecutionContext {
        DispatchMdc.around(DispatchMdc.QUERY, query::class.simpleName ?: "Unknown", query) { ask(query) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R> ask(query: Query<R>): R {
        enforceAuthorization(query, isQuery = true)

        val queryName = query::class.simpleName ?: "Unknown"
        log.debug("Dispatching query: {}", queryName)

        val handler = queryHandlersCache.computeIfAbsent(query::class) {
            findQueryHandler(query::class)
        } as? QueryHandler<Query<R>, R>
            ?: throw IllegalArgumentException("No handler found for query: $queryName").also {
                log.error("No handler found for query: {}", queryName)
            }

        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        return try {
            val result = handler.handle(query)
            log.debug("Query {} completed successfully", queryName)

            // Notify cross-cutting query listeners (read auditing, …). Fired for
            // every query; listeners record only the subset they care about.
            notifyQueryListeners(query, DispatchOutcome.SUCCESS, null)

            result
        } catch (e: Exception) {
            outcome = "failure"
            log.warn("Query {} failed: {}", queryName, e.message)
            notifyQueryListeners(query, DispatchOutcome.FAILURE, e)
            throw e
        } finally {
            sample.stop(
                Timer.builder("epistola.mediator.query.duration")
                    .tag("query", queryName)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }

    private fun <R> withExecutionContext(block: () -> R): R {
        if (MediatorContext.isBound()) {
            return block()
        }
        return MediatorContext.runWithMediator(this, block)
    }

    /**
     * Run the command dispatch inside a Spring transaction. NESTED propagation: a
     * top-level command opens a new transaction; a command dispatched from within
     * another command becomes a JDBC savepoint in the outer transaction — so an outer
     * handler that catches a nested command's failure (continue-on-error install
     * loops) keeps a healthy transaction, while the nested command's writes are
     * rolled back to the savepoint. Uses the transaction manager directly instead of
     * TransactionTemplate to keep exception types transparent. Skipped for
     * [SelfManagedTransaction] commands and when no transaction manager is available
     * (plain unit tests).
     */
    private fun <R> inCommandTransaction(command: Command<*>, block: () -> R): R {
        if (transactionManager == null || command is SelfManagedTransaction) {
            return block()
        }
        val status = transactionManager.getTransaction(
            DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_NESTED),
        )
        try {
            val result = block()
            transactionManager.commit(status)
            return result
        } catch (e: Throwable) {
            if (!status.isCompleted) {
                try {
                    transactionManager.rollback(status)
                } catch (rollbackFailure: Exception) {
                    e.addSuppressed(rollbackFailure)
                }
            }
            throw e
        }
    }

    /**
     * The write guard of ADR 0033: a command that targets a released version is refused, whatever
     * the catalog's editability. A release is read-only; changing it means starting a draft from it.
     */
    private fun refuseWriteToRelease(command: Command<*>) {
        val target = (command as? CatalogReleaseScoped)?.catalogVersion ?: return
        val released = target.draft as? app.epistola.suite.catalog.releases.DraftRef.Released ?: return
        throw app.epistola.suite.catalog.ReleasedVersionReadOnlyException(target.catalog, released.version)
    }

    private fun <R> invokeEventHandlers(command: Command<R>, result: R, phase: EventPhase) {
        val handlers = findEventHandlers(command::class)
            .filter { it.phase == phase }

        for (handler in handlers) {
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as EventHandler<Command<R>>).on(command, result)
            } catch (e: Exception) {
                if (phase == EventPhase.IMMEDIATE) {
                    // IMMEDIATE handlers propagate exceptions (can roll back command)
                    throw e
                } else {
                    // AFTER_COMMIT handlers log but don't propagate
                    log.error(
                        "Event handler failed for {}: {}",
                        command::class.simpleName,
                        e.message,
                        e,
                    )
                }
            }
        }
    }

    /**
     * Notify every cross-cutting [CommandListener] of a dispatched command. Each
     * listener is isolated: a listener that throws is logged and skipped so it can
     * affect neither the command nor the other listeners (the contract says
     * listeners must not throw, but we enforce it defensively).
     */
    private fun notifyCommandListeners(command: Command<*>, outcome: DispatchOutcome, error: Throwable?) {
        for (listener in commandListeners) {
            try {
                listener.onCommand(command, outcome, error)
            } catch (e: Exception) {
                log.error(
                    "Command listener {} failed for {}: {}",
                    listener::class.simpleName,
                    command::class.simpleName,
                    e.message,
                    e,
                )
            }
        }
    }

    /** Read-side counterpart of [notifyCommandListeners]; same error isolation. */
    private fun notifyQueryListeners(query: Query<*>, outcome: DispatchOutcome, error: Throwable?) {
        for (listener in queryListeners) {
            try {
                listener.onQuery(query, outcome, error)
            } catch (e: Exception) {
                log.error(
                    "Query listener {} failed for {}: {}",
                    listener::class.simpleName,
                    query::class.simpleName,
                    e.message,
                    e,
                )
            }
        }
    }

    private fun findEventHandlers(commandClass: KClass<*>): List<EventHandler<*>> = eventHandlersCache.computeIfAbsent(commandClass) {
        val allHandlers = applicationContext.getBeansOfType(EventHandler::class.java).values.toList()
        allHandlers.filter { handler ->
            extractMessageType(handler, EventHandler::class) == commandClass
        }
    }

    private fun findCommandHandler(commandClass: KClass<*>): CommandHandler<*, *> {
        val allHandlers = applicationContext.getBeansOfType(CommandHandler::class.java).values
        return allHandlers.find { handler ->
            extractMessageType(handler, CommandHandler::class) == commandClass
        } ?: throw IllegalArgumentException("No handler found for command: ${commandClass.simpleName}")
    }

    private fun findQueryHandler(queryClass: KClass<*>): QueryHandler<*, *> {
        val allHandlers = applicationContext.getBeansOfType(QueryHandler::class.java).values
        return allHandlers.find { handler ->
            extractMessageType(handler, QueryHandler::class) == queryClass
        } ?: throw IllegalArgumentException("No handler found for query: ${queryClass.simpleName}")
    }

    /** Runs [block], handling [command], as an authorized command of its tenant when it is one ([AuthorizedOperation]). */
    private fun <T> handlingIfAuthorized(command: Command<*>, block: () -> T): T = if (command is RequiresPermission) AuthorizedOperation.handlingCommand(command.tenantKey, block) else block()

    private fun enforceAuthorization(message: Any, isQuery: Boolean = false) {
        when (message) {
            is SystemInternal -> { /* no-op: system-internal operations bypass auth */ }

            // Part of what is already authorized (AuthorizedOperation): a query an authorized operation
            // reads, or a message a command's handler builds on (partOfCurrentCommand).
            is RequiresPermission if AuthorizedOperation.covers(message.tenantKey, isQuery) -> requireTenantAccess(message.tenantKey)

            is RequiresPermission -> {
                // A permission a platform role grants in every tenant (PLATFORM_OBSERVER's reads)
                // needs no membership there.
                if (!SecurityContext.current().grantsInEveryTenant(message.permission)) requireTenantAccess(message.tenantKey)
                requirePermission(message.tenantKey, message.permission)
            }

            is RequiresPlatformRole -> requirePlatformRole(message.platformRole, message.alsoAllowedPlatformRoles)

            is RequiresAuthentication -> {
                currentUser()
                if (message is TenantScoped) requireTenantAccess(message.tenantKey)
            }

            is Authorized -> error("Unhandled Authorized subtype: ${message::class.simpleName}")

            else -> error("${message::class.simpleName} must implement Authorized")
        }
    }

    private fun extractMessageType(
        handler: Any,
        handlerInterface: KClass<*>,
    ): KClass<*> {
        val supertype =
            handler::class.allSupertypes.find { it.classifier == handlerInterface }
                ?: throw IllegalStateException(
                    "Handler ${handler::class.simpleName} must implement $handlerInterface",
                )

        val typeArgument = supertype.arguments.firstOrNull()?.type?.classifier as? KClass<*>
        return typeArgument
            ?: throw IllegalStateException(
                "Could not extract message type from handler ${handler::class.simpleName}",
            )
    }
}
