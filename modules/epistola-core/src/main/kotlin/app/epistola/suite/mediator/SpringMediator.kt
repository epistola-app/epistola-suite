package app.epistola.suite.mediator

import app.epistola.suite.common.TenantScoped
import app.epistola.suite.security.Authorized
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresAuthentication
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.currentUser
import app.epistola.suite.security.requirePermission
import app.epistola.suite.security.requireTenantAccess
import app.epistola.suite.security.requireTenantManager
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Spring-based implementation of the Mediator pattern with eventing.
 *
 * Automatically discovers CommandHandler and QueryHandler beans and routes
 * commands/queries to the appropriate handler.
 *
 * After each successful command:
 * 1. IMMEDIATE EventHandlers are invoked (same call stack, may propagate exceptions)
 * 2. CommandCompleted event is published to Spring's event system
 * 3. AFTER_COMMIT EventHandlers are invoked via TransactionalEventListener
 * 4. EventLogSubscriber persists the event to the audit trail
 *
 * Uses lazy handler discovery to support handlers that are initialized late
 * (e.g., handlers with Spring Batch job dependencies).
 */
@Component
class SpringMediator(
    private val applicationContext: ApplicationContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
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
    override fun <R> send(command: Command<R>): R {
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
        return try {
            val result = handler.handle(command)
            logger.debug("Command {} completed successfully", commandName)

            // Phase 1: Invoke IMMEDIATE event handlers (same transaction/call stack)
            invokeEventHandlers(command, result, EventPhase.IMMEDIATE)

            // Phase 2: Publish Spring event for AFTER_COMMIT handlers and EventLogSubscriber
            eventPublisher.publishEvent(CommandCompleted(command, result))

            result
        } catch (e: Exception) {
            outcome = "failure"
            logger.warn("Command {} failed: {}", commandName, e.message)
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
    override fun <R> query(query: Query<R>): R {
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
        return try {
            val result = handler.handle(query)
            logger.debug("Query {} completed successfully", queryName)
            result
        } catch (e: Exception) {
            outcome = "failure"
            logger.warn("Query {} failed: {}", queryName, e.message)
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
            is RequiresPlatformRole -> {
                when (message.platformRole) {
                    PlatformRole.TENANT_MANAGER -> requireTenantManager()
                }
            }
            is RequiresAuthentication -> {
                currentUser()
                if (message is TenantScoped) requireTenantAccess(message.tenantId)
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
