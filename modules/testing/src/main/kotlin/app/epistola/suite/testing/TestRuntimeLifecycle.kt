package app.epistola.suite.testing

import app.epistola.suite.testing.metrics.TestRunMetrics
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates JVM-wide test resources that are shared across Spring test contexts.
 *
 * Spring closes cached test contexts from a JVM shutdown hook. Testcontainers also
 * cleans up from shutdown hooks. Since hook ordering is undefined, a raw container
 * hook can stop Postgres while Spring beans are still running `@PreDestroy` logic.
 * This coordinator gives test workers an explicit order: close Spring contexts,
 * then stop the shared Postgres container.
 */
object TestRuntimeLifecycle {
    private val logger = LoggerFactory.getLogger(TestRuntimeLifecycle::class.java)
    private val lock = Any()
    private val shutdownStarted = AtomicBoolean(false)
    private val contexts = Collections.newSetFromMap(IdentityHashMap<ConfigurableApplicationContext, Boolean>())

    @Volatile
    private var sharedPostgres: PostgreSQLContainer? = null

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread(::shutdown, "epistola-test-runtime-shutdown"),
        )
    }

    fun registerContext(context: ConfigurableApplicationContext) {
        synchronized(lock) {
            if (shutdownStarted.get()) return
            contexts.add(context)
        }
        context.addApplicationListener { event ->
            if (event is ContextClosedEvent) {
                synchronized(lock) {
                    contexts.remove(context)
                }
            }
        }
    }

    fun postgres(): PostgreSQLContainer {
        sharedPostgres?.let { return it }
        synchronized(lock) {
            sharedPostgres?.let { return it }
            check(!shutdownStarted.get()) { "Test runtime is already shutting down" }
            val container = PostgreSQLContainer(DockerImageName.parse("postgres:17"))
                .withTmpFs(mapOf("/var/lib/postgresql/data" to "rw"))
            val startNanos = System.nanoTime()
            container.start()
            TestRunMetrics.recordPostgresStartupNanos(System.nanoTime() - startNanos)
            sharedPostgres = container
            return container
        }
    }

    fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        closeSpringContexts()
        stopPostgres()
    }

    private fun closeSpringContexts() {
        val snapshot = synchronized(lock) {
            contexts.toList()
        }.sortedByDescending { it.parentDepth() }

        snapshot.forEach { context ->
            if (context.isClosed) return@forEach
            try {
                context.close()
            } catch (e: Exception) {
                logger.warn("Failed to close Spring test context during ordered test shutdown", e)
            }
        }

        synchronized(lock) {
            contexts.removeAll(snapshot.toSet())
        }
    }

    private fun stopPostgres() {
        val container = synchronized(lock) {
            val current = sharedPostgres
            sharedPostgres = null
            current
        } ?: return

        try {
            container.stop()
        } catch (e: Exception) {
            logger.warn("Failed to stop shared test Postgres container", e)
        }
    }

    private fun ConfigurableApplicationContext.parentDepth(): Int {
        var depth = 0
        var current = parent
        while (current != null) {
            depth += 1
            current = current.parent
        }
        return depth
    }
}
