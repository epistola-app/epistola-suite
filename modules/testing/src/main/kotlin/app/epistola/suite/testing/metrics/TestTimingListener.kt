package app.epistola.suite.testing.metrics

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-cutting test-run metrics harness. Auto-registered via
 * `META-INF/services/org.junit.platform.launcher.TestExecutionListener`, so it
 * observes every test in every module that depends on `modules:testing` — no
 * per-test code.
 *
 * It times each test class, and at the end of the run writes a machine-readable
 * JSON report (wall time, context boots, tenant bootstraps, slowest classes) to
 * `epistola.test.metrics.outDir` and prints a compact console summary. The JSON
 * is uploaded as a CI artifact so test performance is monitorable over time and
 * regressions (like the cluster-scheduling series that added ~3 min) are caught.
 */
class TestTimingListener : TestExecutionListener {
    private val startNanosByUniqueId = ConcurrentHashMap<String, Long>()

    @Volatile
    private var planStartNanos = 0L

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        planStartNanos = System.nanoTime()
        TestRunMetrics.reset()
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (testIdentifier.isClassContainer()) {
            startNanosByUniqueId[testIdentifier.uniqueId] = System.nanoTime()
        }
    }

    override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
        val start = startNanosByUniqueId.remove(testIdentifier.uniqueId) ?: return
        val millis = (System.nanoTime() - start) / 1_000_000
        TestRunMetrics.recordClassDuration(testIdentifier.className(), millis)
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        if (TestRunMetrics.classDurationsMs.isEmpty()) return
        val wallMillis = (System.nanoTime() - planStartNanos) / 1_000_000
        val ranked = TestRunMetrics.classDurationsMs.entries.sortedByDescending { it.value }
        writeJsonReport(wallMillis, ranked)
        printSummary(wallMillis, ranked)
    }

    private fun TestIdentifier.isClassContainer(): Boolean = isContainer && source.map { it is ClassSource }.orElse(false)

    private fun TestIdentifier.className(): String = source.map { (it as? ClassSource)?.className }.orElse(null) ?: displayName

    private fun writeJsonReport(wallMillis: Long, ranked: List<Map.Entry<String, Long>>) {
        val outDir = System.getProperty("epistola.test.metrics.outDir") ?: return
        val label = System.getProperty("epistola.test.metrics.label") ?: "test"
        val file = File(outDir).apply { mkdirs() }
            .resolve(label.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        file.writeText(
            """
            |{
            |  "label": ${label.quoted()},
            |  "wallMillis": $wallMillis,
            |  "classCount": ${ranked.size},
            |  "contextBoots": ${TestRunMetrics.contextBoots.get()},
            |  "contextBootMillisTotal": ${TestRunMetrics.contextBootMillis.sum()},
            |  "contextBootMillisMax": ${TestRunMetrics.contextBootMillis.maxOrNull() ?: 0},
            |  "postgresStartupMillis": ${TestRunMetrics.postgresStartupMillis.get()},
            |  "tenantsCreated": ${TestRunMetrics.tenantsCreated()},
            |  "classes": [
            |${ranked.joinToString(",\n") { (name, millis) -> """    { "name": ${name.quoted()}, "millis": $millis }""" }}
            |  ],
            |  "commands": [
            |${dispatchJson(TestRunMetrics.commandStats)}
            |  ],
            |  "queries": [
            |${dispatchJson(TestRunMetrics.queryStats)}
            |  ]
            |}
            """.trimMargin() + "\n",
        )
    }

    private fun dispatchJson(stats: Map<String, TestRunMetrics.DispatchStat>): String = stats.entries.sortedByDescending { it.value.totalNanos.get() }.joinToString(",\n") { (name, s) ->
        """    { "name": ${name.quoted()}, "count": ${s.count.get()}, "totalMillis": ${s.totalMillis()} }"""
    }

    private fun printSummary(wallMillis: Long, ranked: List<Map.Entry<String, Long>>) {
        val label = System.getProperty("epistola.test.metrics.label") ?: "test"
        val sb = StringBuilder()
        sb.appendLine()
        val boots = TestRunMetrics.contextBootMillis
        sb.appendLine("──── test-run metrics [$label] ────")
        sb.appendLine(
            "wall=${wallMillis}ms  classes=${ranked.size}  " +
                "tenantsCreated=${TestRunMetrics.tenantsCreated()}",
        )
        sb.appendLine(
            "contextBoots=${TestRunMetrics.contextBoots.get()} " +
                "(total=${boots.sum()}ms, max=${boots.maxOrNull() ?: 0}ms)  " +
                "postgresStartup=${TestRunMetrics.postgresStartupMillis.get()}ms",
        )
        sb.appendLine("slowest classes:")
        ranked.take(TOP_N).forEach { (name, millis) ->
            sb.appendLine("  ${millis.toString().padStart(7)}ms  ${name.substringAfterLast('.')}")
        }
        sb.appendLine("costliest commands (count, total — incl. nested):")
        TestRunMetrics.commandStats.entries.sortedByDescending { it.value.totalNanos.get() }.take(TOP_N).forEach { (name, s) ->
            sb.appendLine("  ${s.totalMillis().toString().padStart(7)}ms  x${s.count.get()}  $name")
        }
        sb.append("──────────────────────────────────")
        println(sb)
    }

    private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val TOP_N = 15
    }
}
