// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.contexts

import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestPlan
import org.springframework.test.context.MergedContextConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many test classes of this run still need each Spring test context.
 *
 * Spring's context cache keeps a context until the JVM exits, because it cannot know whether a later
 * class will ask for it again. This run can: [ContextUsageListener] counts the classes per context
 * before the first test, and [finished] counts each class off once it is done. The class that takes a
 * count to zero was the last to use that context, and [ReleaseContextWhenDoneExtension] closes it.
 *
 * Before the count is taken (a launcher without the listener), nothing is ever the last class, so
 * every context stays open as Spring would keep it.
 */
internal class ContextUsage {
    private val remaining = ConcurrentHashMap<MergedContextConfiguration, AtomicInteger>()

    @Volatile
    private var counted = false

    fun count(testClasses: Collection<Class<*>>) {
        remaining.clear()
        for (testClass in testClasses) {
            val key = ContextKeys.of(testClass) ?: continue
            remaining.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        }
        counted = true
    }

    /** Counts [testClass] off; true when it was the last class of the run that uses its context. */
    fun finished(testClass: Class<*>): Boolean {
        if (!counted) return false
        val key = ContextKeys.of(testClass) ?: return false
        return remaining[key]?.decrementAndGet() == 0
    }

    companion object {
        /** The count of this JVM's run. */
        val run = ContextUsage()
    }
}

/** Takes [ContextUsage]'s count once the run knows its test classes, before the first one starts. */
class ContextUsageListener : TestExecutionListener {
    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        val loader = Thread.currentThread().contextClassLoader
        val classes = testPlan.roots.asSequence()
            .flatMap { testPlan.getDescendants(it) }
            .mapNotNull { (it.source.orElse(null) as? ClassSource)?.className }
            .distinct()
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
            .toList()
        ContextUsage.run.count(classes)
    }
}
