package app.epistola.suite.testing

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared counter for generating unique tenant slugs across test methods.
 *
 * Unlike instance-level counters that reset per test method, this object-level
 * counter ensures tenant IDs never collide across methods within the same JVM.
 * This eliminates the need to delete tenants between tests, avoiding deadlocks
 * with in-flight job poller transactions.
 *
 * Follows the same pattern as [app.epistola.suite.common.TestIdHelpers].
 */
object TestTenantCounter {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun next(namespace: String): Int = counters.computeIfAbsent(namespace) { AtomicInteger(0) }.incrementAndGet()
}
