package app.epistola.suite.mediator

import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import kotlin.reflect.KClass
import kotlin.reflect.full.allSupertypes

/**
 * Spring-based implementation of the Mediator pattern.
 * Automatically discovers CommandHandler and QueryHandler beans and routes
 * commands/queries to the appropriate handler.
 */
@Component
class SpringMediator(
    private val applicationContext: ApplicationContext,
) : Mediator,
    InitializingBean {
    private lateinit var commandHandlers: Map<KClass<*>, CommandHandler<*, *>>
    private lateinit var queryHandlers: Map<KClass<*>, QueryHandler<*, *>>

    override fun afterPropertiesSet() {
        commandHandlers = discoverCommandHandlers()
        queryHandlers = discoverQueryHandlers()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> send(command: Command<R>): R {
        val handler =
            commandHandlers[command::class] as? CommandHandler<Command<R>, R>
                ?: throw IllegalArgumentException("No handler found for command: ${command::class.simpleName}")
        return handler.handle(command)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> query(query: Query<R>): R {
        val handler =
            queryHandlers[query::class] as? QueryHandler<Query<R>, R>
                ?: throw IllegalArgumentException("No handler found for query: ${query::class.simpleName}")
        return handler.handle(query)
    }

    private fun discoverCommandHandlers(): Map<KClass<*>, CommandHandler<*, *>> = applicationContext
        .getBeansOfType(CommandHandler::class.java)
        .values
        .associateBy { handler -> extractMessageType(handler, CommandHandler::class) }

    private fun discoverQueryHandlers(): Map<KClass<*>, QueryHandler<*, *>> = applicationContext
        .getBeansOfType(QueryHandler::class.java)
        .values
        .associateBy { handler -> extractMessageType(handler, QueryHandler::class) }

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
