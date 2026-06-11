package app.epistola.suite.testing.metrics

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Counts and times fresh Spring `ApplicationContext` boots during a test run.
 *
 * Registered globally via `META-INF/spring.factories`, so Spring Boot applies it
 * to every context it builds. A context that is served from the test-context
 * cache (a cache hit) is NOT rebuilt, so this initializer does not run for it —
 * therefore the count equals the number of cache misses, the metric that tracks
 * test-context fragmentation.
 *
 * The initializer runs early (before the bean factory is refreshed), so the span
 * from here to the first `ContextRefreshedEvent` covers the expensive part of a
 * boot — component scan, bean instantiation, autoconfiguration, and Flyway
 * migration of the per-context database. See [TestRunMetrics].
 */
class ContextBootCounter : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val startNanos = System.nanoTime()
        TestRunMetrics.contextBoots.incrementAndGet()
        val recorded = AtomicBoolean(false)
        applicationContext.addApplicationListener(
            ApplicationListener<ContextRefreshedEvent> {
                if (recorded.compareAndSet(false, true)) {
                    TestRunMetrics.recordContextBootNanos(System.nanoTime() - startNanos)
                }
            },
        )
    }
}
