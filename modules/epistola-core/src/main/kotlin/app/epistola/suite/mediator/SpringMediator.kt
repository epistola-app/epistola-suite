// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mediator

import app.epistola.suite.catalog.CatalogNames
import app.epistola.suite.catalog.CatalogNamesApplier
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.common.TenantScoped
import app.epistola.suite.security.Authorized
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
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
import org.springframework.transaction.support.TransactionSynchronizationManager
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
 * A message that names a catalog version ([CatalogReleaseScoped]) runs on that version: the version is
 * resolved before the handler runs, a query that names one runs in a read-only transaction, and
 * [CatalogReleaseTargeting] makes that transaction's content SQL answer the named version for its
 * catalog. Messages it dispatches inherit it; the caller's target is restored afterwards.
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
    private val versionTargeting: CatalogReleaseTargeting? = null,
    private val catalogNamesApplier: CatalogNamesApplier? = null,
) : Mediator {

    private val logger = LoggerFactory.getLogger(SpringMediator::class.java)

    // Cache handlers but allow lazy discovery for late-initialized beans
    // Thread-safe: use ConcurrentHashMap to prevent data races under concurrent requests
    private val commandHandlersCache: MutableMap<KClass<*>, CommandHandler<*, *>> =
        ConcurrentHashMap()
    private val queryHandlersCache: MutableMap<KClass<*>, QueryHandler<*, *>> =
        ConcurrentHashMap()
    private val eventHandlersCache: MutableMap<KClass<*>, List<EventHandler<*>>> =
        ConcurrentHashMap()

    @Suppress("UNCHECKED_CAST")
    override fun <R> send(command: Command<R>): R = withExecutionContext {
        enforceAuthorization(command)

        val commandName = command::class.simpleName ?: "Unknown"
        logger.debug("Dispatching command: {}", commandName)

        val handler = commandHandlersCache.computeIfAbsent(command::class) {
            findCommandHandler(command::class)
        } as? CommandHandler<Command<R>, R>
            ?: throw IllegalArgumentException("No handler found for command: $commandName").also {
                logger.error("No handler found for command: {}", commandName)
            }

        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            // One transaction around handler + IMMEDIATE events + event publication:
            // a throwing IMMEDIATE handler rolls the command back, and AFTER_COMMIT
            // listeners (EventLogSubscriber) bind to this transaction.
            val result = inCommandTransaction(command) {
                // On the catalog version the command names, if any; IMMEDIATE handlers see it too.
                inCatalogNames {
                    inTargetedVersion(command) {
                        val handled = handler.handle(command)
                        logger.debug("Command {} completed successfully", commandName)

                        // Phase 1: Invoke IMMEDIATE event handlers (same transaction/call stack)
                        invokeEventHandlers(command, handled, EventPhase.IMMEDIATE)

                        // Phase 2: Publish Spring event for AFTER_COMMIT handlers and EventLogSubscriber
                        eventPublisher.publishEvent(CommandCompleted(command, handled))

                        handled
                    }
                }
            }

            // Notify cross-cutting listeners (audit, …) of the successful command.
            // After the transaction committed so a handler that rolls the command back
            // is reported as a failure (catch below), not a success.
            notifyCommandListeners(command, DispatchOutcome.SUCCESS, null)

            result
        } catch (failure: Exception) {
            // A key that names several catalogs, refused by the database, is refused in words.
            val e = CatalogNames.ambiguity(failure) ?: failure
            outcome = "failure"
            logger.warn("Command {} failed: {}", commandName, e.message)
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

    @Suppress("UNCHECKED_CAST")
    override fun <R> query(query: Query<R>): R = withExecutionContext {
        enforceAuthorization(query)

        val queryName = query::class.simpleName ?: "Unknown"
        logger.debug("Dispatching query: {}", queryName)

        val handler = queryHandlersCache.computeIfAbsent(query::class) {
            findQueryHandler(query::class)
        } as? QueryHandler<Query<R>, R>
            ?: throw IllegalArgumentException("No handler found for query: $queryName").also {
                logger.error("No handler found for query: {}", queryName)
            }

        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            val result = if (targetOf(query) == null && CatalogNames.current().isEmpty()) {
                handler.handle(query)
            } else {
                inReadTransaction { inCatalogNames { inTargetedVersion(query) { handler.handle(query) } } }
            }
            logger.debug("Query {} completed successfully", queryName)

            // Notify cross-cutting query listeners (read auditing, …). Fired for
            // every query; listeners record only the subset they care about.
            notifyQueryListeners(query, DispatchOutcome.SUCCESS, null)

            result
        } catch (failure: Exception) {
            val e = CatalogNames.ambiguity(failure) ?: failure
            outcome = "failure"
            logger.warn("Query {} failed: {}", queryName, e.message)
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
     * Runs [block] with the catalog names the caller gave ([CatalogNames]) written into the
     * transaction, so `epistola_catalog_id` looks each key up in its namespace; restores the caller's
     * names afterwards, for a nested message that gave others.
     */
    // Not `?.run(block) ?: block()`: a message that answers null would then run a second time, without the names.
    private fun <R> inCatalogNames(block: () -> R): R = if (catalogNamesApplier != null) catalogNamesApplier.run(block) else block()

    private fun targetOf(message: Any) = (message as? CatalogReleaseScoped)?.catalogVersion?.takeIf { versionTargeting != null }

    /** Runs [block] on the catalog version [message] names, if it names one. */
    private fun <R> inTargetedVersion(message: Any, block: () -> R): R {
        val target = targetOf(message) ?: return block()
        val targeting = requireNotNull(versionTargeting)
        return targeting.within(targeting.resolve(target), block)
    }

    /**
     * A query that names a catalog version needs a transaction to carry the target. REQUIRED: one
     * dispatched inside a command joins the command's.
     */
    private fun <R> inReadTransaction(block: () -> R): R {
        if (transactionManager == null) return block()
        val definition = DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED).apply { isReadOnly = true }
        val status = transactionManager.getTransaction(definition)
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
                    logger.error(
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
                logger.error(
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
                logger.error(
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

    private fun enforceAuthorization(message: Any) {
        when (message) {
            is SystemInternal -> { /* no-op: system-internal operations bypass auth */ }

            is RequiresPermission -> {
                requireTenantAccess(message.tenantKey)
                requirePermission(message.tenantKey, message.permission)
            }

            is RequiresPlatformRole -> requirePlatformRole(message.platformRole)

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
