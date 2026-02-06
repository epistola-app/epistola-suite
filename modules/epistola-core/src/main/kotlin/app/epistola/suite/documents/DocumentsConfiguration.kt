package app.epistola.suite.documents

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration for document generation and job polling.
 */
@Configuration
@EnableConfigurationProperties(JobPollingProperties::class)
class DocumentsConfiguration

/**
 * Job polling configuration properties.
 *
 * Controls the behavior of the background job poller that claims and executes
 * document generation requests.
 *
 * @property enabled Enable/disable job polling (default: true)
 * @property intervalMs Polling interval in milliseconds (default: 5000ms = 5 seconds)
 * @property maxConcurrentJobs Maximum number of jobs running concurrently per instance (default: 2)
 * @property staleTimeoutMinutes Timeout for reclaiming stale jobs (default: 10 minutes)
 * @property adaptiveBatch Adaptive batch sizing configuration
 */
@ConfigurationProperties(prefix = "epistola.generation.polling")
data class JobPollingProperties(
    val enabled: Boolean = true,
    val intervalMs: Long = 5000,
    val maxConcurrentJobs: Int = 2,
    val staleTimeoutMinutes: Long = 10,
    val adaptiveBatch: AdaptiveBatchProperties = AdaptiveBatchProperties(),
)

/**
 * Configuration properties for adaptive batch sizing.
 *
 * @property minBatchSize Minimum batch size (never go below this, default: 1)
 * @property maxBatchSize Maximum batch size (should be ~5x maxConcurrentJobs, default: 10)
 * @property fastThresholdMs Processing time threshold for "fast" jobs in milliseconds (default: 2000ms)
 * @property slowThresholdMs Processing time threshold for "slow" jobs in milliseconds (default: 5000ms)
 */
data class AdaptiveBatchProperties(
    val minBatchSize: Int = 1,
    val maxBatchSize: Int = 10,
    val fastThresholdMs: Long = 2000,
    val slowThresholdMs: Long = 5000,
)
