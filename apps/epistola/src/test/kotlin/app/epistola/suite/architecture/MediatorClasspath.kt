package app.epistola.suite.architecture

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.springframework.core.ResolvableType
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * Classpath scanning shared by the architecture tests.
 *
 * Runs on this module's full runtime classpath — the app depends on every feature module,
 * so commands, queries and handlers from epistola-core, loadtest and all epistola-support-*
 * modules are visible here. Test classes and the shared test-infrastructure module are
 * excluded: their fixture commands are not part of the production wiring.
 *
 * Scanning is metadata-based and deliberately does NOT evaluate @Conditional annotations:
 * handlers gated on properties (e.g. epistola.support.enabled) still count as wired.
 */
internal object MediatorClasspath {

    val commands: List<Class<*>> by lazy { scan(Command::class.java) }
    val queries: List<Class<*>> by lazy { scan(Query::class.java) }
    val commandHandlers: List<Class<*>> by lazy { scan(CommandHandler::class.java) }
    val queryHandlers: List<Class<*>> by lazy { scan(QueryHandler::class.java) }

    /**
     * The concrete command/query class a handler is declared for, mirroring how
     * SpringMediator matches handlers to messages (the first type argument of the
     * CommandHandler/QueryHandler interface).
     */
    fun handledMessageType(handler: Class<*>): Class<*>? {
        val handlerInterface = if (CommandHandler::class.java.isAssignableFrom(handler)) {
            CommandHandler::class.java
        } else {
            QueryHandler::class.java
        }
        return ResolvableType.forClass(handler).`as`(handlerInterface).getGeneric(0).resolve()
    }

    private val resolver = PathMatchingResourcePatternResolver()
    private val readerFactory = SimpleMetadataReaderFactory(resolver)

    private val classResources: List<Resource> by lazy {
        resolver.getResources("classpath*:app/epistola/suite/**/*.class")
            .filter { isProductionLocation(it.url.toString()) }
    }

    private fun scan(type: Class<*>): List<Class<*>> {
        val filter = AssignableTypeFilter(type)
        return classResources
            .mapNotNull { resource ->
                val reader = readerFactory.getMetadataReader(resource)
                val metadata = reader.classMetadata
                when {
                    !metadata.isIndependent || metadata.isInterface || metadata.isAbstract -> null
                    !filter.match(reader, readerFactory) -> null
                    else -> Class.forName(metadata.className)
                }
            }
            .sortedBy { it.name }
    }

    private fun isProductionLocation(location: String): Boolean = !location.contains("/classes/kotlin/test/") &&
        !location.contains("/classes/java/test/") &&
        !location.contains("/modules/testing/")
}
