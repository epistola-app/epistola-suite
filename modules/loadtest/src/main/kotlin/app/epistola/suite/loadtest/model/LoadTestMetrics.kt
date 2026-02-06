package app.epistola.suite.loadtest.model

/**
 * Aggregated performance metrics for a load test run.
 *
 * All timing measurements are in milliseconds.
 *
 * @property totalDurationMs Total time from first request start to last request completion
 * @property avgResponseTimeMs Average response time across all successful requests
 * @property minResponseTimeMs Minimum response time observed
 * @property maxResponseTimeMs Maximum response time observed
 * @property p50ResponseTimeMs 50th percentile (median) response time
 * @property p95ResponseTimeMs 95th percentile response time
 * @property p99ResponseTimeMs 99th percentile response time
 * @property requestsPerSecond Throughput (total requests / total duration in seconds)
 * @property successRatePercent Percentage of requests that completed successfully (0-100)
 * @property errorSummary Map of error types to their occurrence counts
 */
data class LoadTestMetrics(
    val totalDurationMs: Long,
    val avgResponseTimeMs: Double,
    val minResponseTimeMs: Long,
    val maxResponseTimeMs: Long,
    val p50ResponseTimeMs: Long,
    val p95ResponseTimeMs: Long,
    val p99ResponseTimeMs: Long,
    val requestsPerSecond: Double,
    val successRatePercent: Double,
    val errorSummary: Map<String, Int>,
)
