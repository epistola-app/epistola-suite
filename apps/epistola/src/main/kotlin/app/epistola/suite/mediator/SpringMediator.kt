package app.epistola.suite.mediator

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Spring-based implementation of the Mediator pattern.
 * Automatically discovers CommandHandler and QueryHandler beans and routes
 * commands/queries to the appropriate handler.
 *
 * Uses lazy handler discovery to support handlers that are initialized late
 * (e.g., handlers with Spring Batch job dependencies).
 */
@Component
class SpringMediator(
    private val applicationContext: ApplicationContext,
) : Mediator {

    private val logger = LoggerFactory.getLogger(SpringMediator::class.java)

    // Cache handlers but allow lazy discovery for late-initialized beans
    private val commandHandlersCache: MutableMap<KClass<*>, CommandHandler<*, *>> = mutableMapOf()
    private val queryHandlersCache: MutableMap<KClass<*>, QueryHandler<*, *>> = mutableMapOf()

    @Suppress("UNCHECKED_CAST")
    override fun <R> send(command: Command<R>): R {
        val commandName = command::class.simpleName
        logger.debug("Dispatching command: {}", commandName)

        val handler = commandHandlersCache.getOrPut(command::class) {
            findCommandHandler(command::class)
        } as? CommandHandler<Command<R>, R>
            ?: throw IllegalArgumentException("No handler found for command: $commandName").also {
                logger.error("No handler found for command: {}", commandName)
            }

        return try {
            val result = handler.handle(command)
            logger.debug("Command {} completed successfully", commandName)
            result
        } catch (e: Exception) {
            logger.warn("Command {} failed: {}", commandName, e.message)
            throw e
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> query(query: Query<R>): R {
        val queryName = query::class.simpleName
        logger.debug("Dispatching query: {}", queryName)

        val handler = queryHandlersCache.getOrPut(query::class) {
            findQueryHandler(query::class)
        } as? QueryHandler<Query<R>, R>
            ?: throw IllegalArgumentException("No handler found for query: $queryName").also {
                logger.error("No handler found for query: {}", queryName)
            }

        return try {
            val result = handler.handle(query)
            logger.debug("Query {} completed successfully", queryName)
            result
        } catch (e: Exception) {
            logger.warn("Query {} failed: {}", queryName, e.message)
            throw e
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
