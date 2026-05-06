package app.epistola.suite.generation.collect.perf

import java.io.File
import java.time.Instant
import kotlin.math.sqrt

/**
 * Tiny helper for perf-test reporting:
 *   - percentile calc over a long list (sample size ~10k–100k, full sort is fine)
 *   - stddev for fairness checks
 *   - CSV append (auto-creates parent dirs + header on first write)
 *   - formatted console table at end of a run
 *
 * Pure Kotlin, zero deps beyond stdlib. Lives next to the perf tests so it
 * doesn't pollute production code with reporting concerns.
 */
internal object PerfReport {

    private val csvHeader = listOf(
        "timestamp",
        "test",
        "params",
        "totalRows",
        "numConsumers",
        "durationMs",
        "throughputMsgPerSec",
        "perConsumerMin",
        "perConsumerMax",
        "perConsumerStddev",
        "pollingEfficiency",
        "hardwareTag",
        "jvm",
    )

    fun percentile(samples: List<Long>, p: Double): Long {
        require(p in 0.0..100.0) { "p must be in 0..100" }
        if (samples.isEmpty()) return 0L
        val sorted = samples.sorted()
        val rank = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    fun stddev(samples: List<Long>): Double {
        if (samples.size < 2) return 0.0
        val mean = samples.average()
        val variance = samples.map { (it - mean) * (it - mean) }.sum() / (samples.size - 1)
        return sqrt(variance)
    }

    fun appendCsv(file: File, row: Map<String, Any?>) {
        file.parentFile?.mkdirs()
        val isNew = !file.exists()
        file.appendText(
            buildString {
                if (isNew) {
                    append(csvHeader.joinToString(","))
                    append("\n")
                }
                append(csvHeader.joinToString(",") { col -> row[col]?.toString() ?: "" })
                append("\n")
            },
        )
    }

    /**
     * Multi-line console block with a leading divider so it stands out in the
     * test logs. Caller passes a list of `key value` pairs; we right-pad keys
     * for alignment.
     */
    fun consoleBlock(title: String, lines: List<Pair<String, String>>) {
        val keyWidth = lines.maxOf { it.first.length } + 2
        val sb = StringBuilder()
        sb.append("\n========== $title ==========\n")
        for ((k, v) in lines) {
            sb.append(k.padEnd(keyWidth)).append(v).append('\n')
        }
        sb.append("=".repeat(title.length + 22)).append('\n')
        println(sb.toString())
    }

    fun nowIso(): String = Instant.now().toString()

    fun jvmTag(): String = "${System.getProperty("java.vm.name")} ${System.getProperty("java.runtime.version")}"

    /**
     * Hardware identifier passed via `-Dperf.hardware=…` so reports done on
     * different machines (laptop vs CI vs prod-equiv) can be told apart in
     * the CSV. Defaults to `local-${user.name}`.
     */
    fun hardwareTag(): String = System.getProperty("perf.hardware") ?: "local-${System.getProperty("user.name")}"
}
