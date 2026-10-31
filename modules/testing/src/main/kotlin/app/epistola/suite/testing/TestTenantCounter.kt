// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
 * Follows the same pattern as [app.epistola.suite.testing.TestIdHelpers].
 */
object TestTenantCounter {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** The test that drew each number, keyed `namespace#number`, so a report can name it. */
    private val drawnBy = ConcurrentHashMap<String, String>()

    /** The test running on this thread, set by [app.epistola.suite.testing.invariants.CatalogInvariantsExtension]. */
    internal val currentTest = ThreadLocal<String>()

    fun next(namespace: String): Int = counters.computeIfAbsent(namespace) { AtomicInteger(0) }.incrementAndGet()
        .also { number -> currentTest.get()?.let { drawnBy["$namespace#$number"] = it } }

    /** Whether a tenant slug was handed out in [namespace]. */
    fun used(namespace: String): Boolean = counters.containsKey(namespace)

    /** The test that created the tenant [slug] (`namespace-N` or `namespace-sN`), when known. */
    fun testOf(slug: String): String? = SLUG.matchEntire(slug)?.let { drawnBy["${it.groupValues[1]}#${it.groupValues[2]}"] }

    private val SLUG = Regex("""(.*)-s?(\d+)""")
}
