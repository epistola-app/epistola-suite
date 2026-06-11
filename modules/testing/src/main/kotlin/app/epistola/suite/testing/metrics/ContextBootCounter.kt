package app.epistola.suite.testing.metrics

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext

/**
 * Counts fresh Spring `ApplicationContext` boots during a test run.
 *
 * Registered globally via `META-INF/spring.factories`, so Spring Boot applies it
 * to every context it builds. A context that is served from the test-context
 * cache (a cache hit) is NOT rebuilt, so this initializer does not run for it —
 * therefore the count equals the number of cache misses, the metric that tracks
 * test-context fragmentation. See [TestRunMetrics].
 */
class ContextBootCounter : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        TestRunMetrics.contextBoots.incrementAndGet()
    }
}
